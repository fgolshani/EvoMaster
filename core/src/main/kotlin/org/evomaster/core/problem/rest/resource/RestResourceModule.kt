package org.evomaster.core.problem.rest.resource

import com.google.inject.AbstractModule
import com.google.inject.TypeLiteral
import org.evomaster.core.problem.rest.resource.service.*
import org.evomaster.core.problem.rest.service.AbstractRestFitness
import org.evomaster.core.remote.service.RemoteController
import org.evomaster.core.search.service.mutator.StandardMutator
import org.evomaster.core.search.service.*
import org.evomaster.core.search.service.mutator.Mutator
import org.evomaster.core.search.service.mutator.StructureMutator


class RestResourceModule : AbstractModule(){

    override fun configure() {
        bind(object : TypeLiteral<Sampler<ResourceRestIndividual>>() {})
                .to(ResourceRestSampler::class.java)
                .asEagerSingleton()

        bind(object : TypeLiteral<Sampler<*>>() {})
                .to(ResourceRestSampler::class.java)
                .asEagerSingleton()

        bind(ResourceRestSampler::class.java)
                .asEagerSingleton()

        bind(object : TypeLiteral<FitnessFunction<ResourceRestIndividual>>() {})
                .to(ResourceRestFitness::class.java)
                .asEagerSingleton()

        bind(object : TypeLiteral<AbstractRestFitness<ResourceRestIndividual>>() {})
                .to(ResourceRestFitness::class.java)
                .asEagerSingleton()

        bind(object : TypeLiteral<Archive<ResourceRestIndividual>>() {})
                .asEagerSingleton()

        bind(object : TypeLiteral<Archive<*>>() {})
                .to(object : TypeLiteral<Archive<ResourceRestIndividual>>() {})

        bind(RemoteController::class.java)
                .asEagerSingleton()

        bind(object : TypeLiteral<Mutator<ResourceRestIndividual>>() {})
                .to(ResourceRestMutator::class.java)
                .asEagerSingleton()

        bind(object : TypeLiteral<StandardMutator<ResourceRestIndividual>>() {})
                .to(ResourceRestMutator::class.java)
                .asEagerSingleton()

        bind(ResourceRestMutator::class.java)
                .asEagerSingleton()

        bind(StructureMutator::class.java)
                .to(ResourceRestStructureMutator::class.java)
                .asEagerSingleton()

        bind(ResourceManageService::class.java)
                .asEagerSingleton()

        bind(DependencyAndDBManager::class.java)
                .asEagerSingleton()

    }
}