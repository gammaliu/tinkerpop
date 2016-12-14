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
import org.apache.tinkerpop.gremlin.process.actor.traversal.message.Terminate;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.Barrier;
import org.apache.tinkerpop.gremlin.process.traversal.step.GraphComputing;
import org.apache.tinkerpop.gremlin.process.traversal.step.LocalBarrier;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TailGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GroupStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.OrderedTraverser;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalMatrix;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Partitioner;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
final class TraversalMasterProgram<M> implements ActorProgram.Master<M> {


    private final Actor.Master master;
    private final Traversal.Admin<?, ?> traversal;
    private final TraversalMatrix<?, ?> matrix;
    private final Partitioner partitioner;
    private Map<String, Barrier> barriers = new HashMap<>();
    private final TraverserSet<?> results;
    private Address.Worker leaderWorker;
    private int orderCounter = -1;

    public TraversalMasterProgram(final Actor.Master master, final Traversal.Admin<?, ?> traversal, final Partitioner partitioner, final TraverserSet<?> results) {
        this.traversal = traversal;
        //System.out.println("master[created]: " + master.address().location());
        //System.out.println(this.traversal);
        this.matrix = new TraversalMatrix<>(this.traversal);
        this.partitioner = partitioner;
        this.results = results;
        this.master = master;
    }

    @Override
    public void setup() {
        this.leaderWorker = this.master.workers().get(0);
        this.broadcast(StartMessage.instance());
        this.master.send(this.leaderWorker, Terminate.MAYBE);
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
        } else if (message instanceof Terminate) {
            assert Terminate.YES == message;
            if (!this.barriers.isEmpty()) {
                for (final Barrier barrier : this.barriers.values()) {
                    final Step<?, ?> step = (Step) barrier;
                    if (!(barrier instanceof LocalBarrier)) {
                        this.orderBarrier(step);
                        if (step instanceof OrderGlobalStep) this.orderCounter = 0;
                        while (step.hasNext()) {
                            this.sendTraverser(-1 == this.orderCounter ?
                                    step.next() :
                                    new OrderedTraverser<>(step.next(), this.orderCounter++));
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
                this.master.send(this.leaderWorker, Terminate.MAYBE);
            } else {
                while (this.traversal.hasNext()) {
                    this.results.add((Traverser.Admin) this.traversal.nextTraverser());
                }
                if (this.orderCounter != -1)
                    this.results.sort((a, b) -> Integer.compare(((OrderedTraverser<?>) a).order(), ((OrderedTraverser<?>) b).order()));

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
        for (final Address.Worker worker : this.master.workers()) {
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
            if (step instanceof Barrier) {
                this.barriers.put(step.getId(), (Barrier) step);
            } else {
                while (step.hasNext()) {
                    this.processTraverser(step.next());
                }
            }
        }
    }

    private void sendTraverser(final Traverser.Admin traverser) {
        if (traverser.isHalted())
            this.results.add(traverser);
        else if (traverser.get() instanceof Element)
            this.master.send(this.master.workers().get(this.partitioner.getPartitions().indexOf(this.partitioner.getPartition((Element) traverser.get()))), traverser);
        else
            this.master.send(this.master.address(), traverser);
    }

    private void orderBarrier(final Step step) {
        if (this.orderCounter != -1 && step instanceof Barrier && (step instanceof RangeGlobalStep || step instanceof TailGlobalStep)) {
            final Barrier barrier = (Barrier) step;
            final TraverserSet<?> rangingBarrier = (TraverserSet<?>) barrier.nextBarrier();
            rangingBarrier.sort((a, b) -> Integer.compare(((OrderedTraverser<?>) a).order(), ((OrderedTraverser<?>) b).order()));
            barrier.addBarrier(rangingBarrier);
        }
    }
}
