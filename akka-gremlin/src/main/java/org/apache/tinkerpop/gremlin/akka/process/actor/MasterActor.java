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

package org.apache.tinkerpop.gremlin.akka.process.actor;

import akka.actor.AbstractActor;
import akka.actor.ActorSelection;
import akka.actor.Props;
import akka.dispatch.RequiresMessageQueue;
import akka.japi.pf.ReceiveBuilder;
import org.apache.tinkerpop.gremlin.process.actor.Actor;
import org.apache.tinkerpop.gremlin.process.actor.ActorProgram;
import org.apache.tinkerpop.gremlin.process.actor.Address;
import org.apache.tinkerpop.gremlin.structure.Partition;
import org.apache.tinkerpop.gremlin.structure.Partitioner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public final class MasterActor extends AbstractActor implements RequiresMessageQueue<TraverserMailbox.TraverserSetSemantics>, Actor.Master {

    private final Address.Master master;
    private final List<Address.Worker> workers;
    private final Map<Address, ActorSelection> actors = new HashMap<>();

    public MasterActor(final ActorProgram program, final Partitioner partitioner) {
        this.master = new Address.Master(self().path().toString());
        this.workers = new ArrayList<>();
        final List<Partition> partitions = partitioner.getPartitions();
        for (final Partition partition : partitions) {
            this.workers.add(new Address.Worker("worker-" + partition.hashCode()));
            context().actorOf(Props.create(WorkerActor.class, program, partitioner, partition), "worker-" + partition.hashCode());
        }
        final ActorProgram.Master masterProgram = program.createMasterProgram(this);
        receive(ReceiveBuilder.matchAny(masterProgram::execute).build());
        masterProgram.setup();
    }

    @Override
    public <M> void send(final Address toActor, final M message) {
        ActorSelection actor = this.actors.get(toActor);
        if (null == actor) {
            actor = context().actorSelection(toActor.location());
            this.actors.put(toActor, actor);
        }
        actor.tell(message, self());
    }

    @Override
    public List<Address.Worker> workers() {
        return this.workers;
    }

    @Override
    public Address.Master address() {
        return this.master;
    }

    @Override
    public void close() {
        context().system().terminate();
    }

}
