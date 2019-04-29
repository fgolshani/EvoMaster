package org.evomaster.core.problem.rest.resource.service

import com.google.inject.Inject
import org.evomaster.core.problem.rest.resource.ResourceRestIndividual
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.gene.*
import org.evomaster.core.search.service.Archive
import org.evomaster.core.search.service.mutator.StandardMutator

class RestResourceMutator : StandardMutator<ResourceRestIndividual>() {

    @Inject
    private lateinit var rm :ResourceManageService

    @Inject
    private lateinit var archive: Archive<ResourceRestIndividual>


    override fun postActionAfterMutation(mutatedIndividual: ResourceRestIndividual) {
        super.postActionAfterMutation(mutatedIndividual)
        mutatedIndividual.getResourceCalls().forEach { rm.repairRestResourceCalls(it) }
        mutatedIndividual.repairDBActions()
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

    override fun update(previous: EvaluatedIndividual<ResourceRestIndividual>, mutated: EvaluatedIndividual<ResourceRestIndividual>, mutatedGenes : MutableList<Gene>) {
        if(mutatedGenes.isEmpty() && (previous.individual.getResourceCalls().size > 1 || mutated.individual.getResourceCalls().size > 1) && config.probOfEnablingResourceDependencyHeuristics > 0){
            //only for structure mutation
            val isWorse = previous.fitness.subsumes(mutated.fitness, archive.notCoveredTargets())
            val isBetter = archive.wouldReachNewTarget(mutated) || !isWorse
            rm.detectDependency(previous, mutated, if(isBetter) 1 else if(isWorse) -1 else 0)
        }
    }

}