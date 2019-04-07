package org.evomaster.core.problem.rest.resource.service

import com.google.inject.Inject
import org.evomaster.core.problem.rest.resource.ResourceRestIndividual
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.gene.*
import org.evomaster.core.search.service.mutator.StandardMutator

class RestResourceMutator : StandardMutator<ResourceRestIndividual>() {

    @Inject
    private lateinit var rm :ResourceManageService


    override fun postActionAfterMutation(mutatedIndividual: ResourceRestIndividual) {
        super.postActionAfterMutation(mutatedIndividual)
        mutatedIndividual.getResourceCalls().forEach { rm.repairRestResourceCalls(it) }
    }

    override fun doesStructureMutation(individual : ResourceRestIndividual): Boolean {

        return individual.canMutateStructure() &&
                (!rm.onlyIndependentResource()) && // if all resources are asserted independent, there is no point to do structure mutation
                config.maxTestSize > 1 &&
                randomness.nextBoolean(config.structureMutationProbability)
    }

    override fun genesToMutation(individual: ResourceRestIndividual, evi : EvaluatedIndividual<ResourceRestIndividual>): List<Gene> {
        //if data of resource call is existing from db, select other row
        val selectAction = individual.getResourceCalls().filter { it.dbActions.isNotEmpty() && it.dbActions.last().representExistingData }
        if(selectAction.isNotEmpty())
            return randomness.choose(selectAction).seeGenes()
        return individual.getResourceCalls().flatMap { it.seeGenes() }.filter(Gene::isMutable)
    }

}