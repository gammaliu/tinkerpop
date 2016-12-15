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

package org.apache.tinkerpop.gremlin.process.actor;

import org.apache.commons.configuration.Configuration;
import org.apache.tinkerpop.gremlin.structure.Graph;

import java.util.List;
import java.util.Optional;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public interface ActorProgram<M> extends Cloneable {

    public static final String ACTOR_PROGRAM = "gremlin.actorProgram";

    /**
     * When it is necessary to store the state of the ActorProgram, this method is called.
     * This is typically required when the ActorProgram needs to be serialized to another machine.
     * Note that what is stored is simply the instance/configuration state, not any processed data.
     * The default implementation provided simply stores the ActorProgarm class name for reflective reconstruction.
     * It is typically a good idea to ActorProgram.super.storeState().
     *
     * @param configuration the configuration to store the state of the ActorProgram in.
     */
    public default void storeState(final Configuration configuration) {
        configuration.setProperty(ACTOR_PROGRAM, this.getClass().getName());
    }

    /**
     * When it is necessary to load the state of the ActorProgram, this method is called.
     * This is typically required when the ActorProgram needs to be serialized to another machine.
     * Note that what is loaded is simply the instance state, not any processed data.
     *
     * @param graph         the graph that the ActorProgram will run against
     * @param configuration the configuration to load the state of the ActorProgram from.
     */
    public default void loadState(final Graph graph, final Configuration configuration) {

    }

    /**
     * Create the {@link org.apache.tinkerpop.gremlin.process.actor.Actor.Worker} program.
     * This is typically used by {@link Worker} to spawn its program.
     *
     * @param worker the worker actor creating the worker program
     * @return the worker program
     */
    public Worker createWorkerProgram(final Actor.Worker worker);

    /**
     * Create the {@link org.apache.tinkerpop.gremlin.process.actor.Actor.Master} program.
     * This is typically used by {@link Master} to spawn its program.
     *
     * @param master the master actor creating the master program
     * @return the master program
     */
    public Master createMasterProgram(final Actor.Master master);

    /**
     * Get the ordered list of message classes where order determines the priority
     * of message reception by the respective {@link Actor}. For instance,
     * if an {@link Actor} has a message of type {@code X} and a message of type {@code Y}
     * in its message buffer, and {@code X} has a higher priority, it will be fetched
     * first from the buffer. If no list is provided then its FIFO.
     * The default implementation returns an {@link Optional#empty()}.
     *
     * @return the optional ordered list of message priorities.
     */
    public default Optional<List<Class>> getMessagePriorities() {
        return Optional.empty();
    }

    /**
     * When multiple workers on a single machine need ActorProgram instances, it is possible to use clone.
     * This will provide a speedier way of generating instances, over the {@link ActorProgram#storeState} and {@link ActorProgram#loadState} model.
     * The default implementation simply returns the object as it assumes that the ActorProgram instance is a stateless singleton.
     *
     * @return A clone of the VertexProgram object
     */
    @SuppressWarnings("CloneDoesntDeclareCloneNotSupportedException")
    public ActorProgram<M> clone();

    public static interface Worker<M> {
        public void setup();

        public void execute(final M message);

        public void terminate();

    }

    public static interface Master<M> {
        public void setup();

        public void execute(final M message);

        public void terminate();

    }

}
