/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.tinkerpop.gremlin.process.actor.traversal;

import org.apache.tinkerpop.gremlin.process.actor.Actor;
import org.apache.tinkerpop.gremlin.process.actor.ActorProgram;
import org.apache.tinkerpop.gremlin.process.actor.Address;
import org.apache.tinkerpop.gremlin.process.actor.traversal.message.BarrierAddMessage;
import org.apache.tinkerpop.gremlin.process.actor.traversal.message.BarrierDoneMessage;
import org.apache.tinkerpop.gremlin.process.actor.traversal.message.SideEffectAddMessage;
import org.apache.tinkerpop.gremlin.process.actor.traversal.message.SideEffectSetMessage;
import org.apache.tinkerpop.gremlin.process.actor.traversal.message.StartMessage;
import org.apache.tinkerpop.gremlin.process.actor.traversal.message.VoteToHaltMessage;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.Barrier;
import org.apache.tinkerpop.gremlin.process.traversal.step.GraphComputing;
import org.apache.tinkerpop.gremlin.process.traversal.step.LocalBarrier;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalMatrix;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Partitioner;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class TraversalMasterProgram<M> implements ActorProgram.Master<M> {

    private final Actor.Master master;
    private final Map<String, Address.Worker> workers = new HashMap<>();
    private final Traversal.Admin<?, ?> traversal;
    private final TraversalMatrix<?, ?> matrix;
    private final Partitioner partitioner;
    private Map<String, Barrier> barriers = new HashMap<>();
    private final TraverserSet<?> results;
    private final String leaderWorker;

    public TraversalMasterProgram(final Actor.Master master, final Traversal.Admin<?, ?> traversal, final Partitioner partitioner, final TraverserSet<?> results) {
        this.traversal = traversal;
        System.out.println("master[created]: " + master.address().location());
        System.out.println(this.traversal);
        this.matrix = new TraversalMatrix<>(this.traversal);
        this.partitioner = partitioner;
        this.results = results;
        this.master = master;
        this.leaderWorker = "worker-" + this.partitioner.getPartitions().get(0).hashCode();
    }

    @Override
    public void setup() {
        for (final Address.Worker worker : master.workers()) {
            this.workers.put(worker.location(), worker);
        }
        this.broadcast(StartMessage.instance());

    }

    @Override
    public void execute(final M message) {
        if (message instanceof Traverser.Admin) {
            this.processTraverser((Traverser.Admin) message);
        } else if (message instanceof BarrierAddMessage) {
            final Barrier barrier = (Barrier) this.matrix.getStepById(((BarrierAddMessage) message).getStepId());
            final Step<?, ?> step = (Step) barrier;
            GraphComputing.atMaster(step, true);
            barrier.addBarrier(((BarrierAddMessage) message).getBarrier());
            this.barriers.put(step.getId(), barrier);
        } else if (message instanceof SideEffectAddMessage) {
            this.traversal.getSideEffects().add(((SideEffectAddMessage) message).getKey(), ((SideEffectAddMessage) message).getValue());
        } else if (message instanceof VoteToHaltMessage) {
            if (!this.barriers.isEmpty()) {
                for (final Barrier barrier : this.barriers.values()) {
                    final Step<?, ?> step = (Step) barrier;
                    if (!(barrier instanceof LocalBarrier)) {
                        while (step.hasNext()) {
                            this.sendTraverser(step.next());
                        }
                    } else {
                        this.traversal.getSideEffects().forEach((k, v) -> {
                            this.broadcast(new SideEffectSetMessage(k, v));
                        });
                        this.broadcast(new BarrierDoneMessage(barrier));
                        barrier.done();
                    }
                }
                this.barriers.clear();
                this.master.send(this.workers.get(this.leaderWorker), StartMessage.instance());
            } else {
                while (this.traversal.hasNext()) {
                    this.results.add((Traverser.Admin) this.traversal.nextTraverser());
                }
                this.master.close();
            }
        } else {
            throw new IllegalStateException("Unknown message:" + message);
        }
    }

    @Override
    public void terminate() {

    }

    private void broadcast(final Object message) {
        for (final Address.Worker worker : this.workers.values()) {
            this.master.send(worker, message);
        }
    }

    private void processTraverser(final Traverser.Admin traverser) {
        if (traverser.isHalted() || traverser.get() instanceof Element) {
            this.sendTraverser(traverser);
        } else {
            final Step<?, ?> step = this.matrix.<Object, Object, Step<Object, Object>>getStepById(traverser.getStepId());
            GraphComputing.atMaster(step, true);
            step.addStart(traverser);
            while (step.hasNext()) {
                this.processTraverser(step.next());
            }
        }
    }

    private void sendTraverser(final Traverser.Admin traverser) {
        if (traverser.isHalted())
            this.results.add(traverser);
        else if (traverser.get() instanceof Element)
            this.master.send(this.workers.get("worker-" + this.partitioner.getPartition((Element) traverser.get()).hashCode()), traverser);
        else
            this.master.send(this.master.address(), traverser);
    }


}
