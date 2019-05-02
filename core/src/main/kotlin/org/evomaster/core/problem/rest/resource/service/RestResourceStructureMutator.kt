package org.evomaster.core.problem.rest.resource.service

import com.google.inject.Inject
import org.evomaster.core.problem.rest.resource.ResourceRestIndividual
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.Individual
import org.evomaster.core.search.service.mutator.StructureMutator

class RestResourceStructureMutator : StructureMutator() {

    @Inject
    private lateinit var rm : ResourceManageService

    override fun mutateStructure(individual: Individual) {
        if(individual !is ResourceRestIndividual)
            throw IllegalArgumentException("Invalid individual type")

        mutateRestResourceCalls(individual)
    }

    private fun mutateRestResourceCalls(ind: ResourceRestIndividual) {

        val num = ind.getResourceCalls().size
        val executedStructureMutator = randomness.choose(
                MutationType.values()
                        .filter {  num >= it.minSize }
                        .filterNot{
                            (ind.seeActions().size == config.maxTestSize && it == MutationType.ADD) ||
                                    //if the individual includes all resources, ADD and REPLACE are not applicable
                                    (ind.getResourceCalls().map {
                                        it.resourceInstance.getKey()
                                    }.toSet().size >= rm.getResourceCluster().size && (it == MutationType.ADD || it == MutationType.REPLACE))
                        })
        when(executedStructureMutator){
            MutationType.ADD -> handleAdd(ind)
            MutationType.DELETE -> handleDelete(ind)
            MutationType.SWAP -> handleSwap(ind)
            MutationType.REPLACE -> handleReplace(ind)
            MutationType.MODIFY -> handleModify(ind)
        }

        ind.repairDBActions()
    }

    /**
     * the class defines possible methods to mutate ResourceRestIndividual regarding its resources
     */
    enum class MutationType(val minSize: Int){
        DELETE(2),
        SWAP(2),
        ADD(1),
        REPLACE(1),
        MODIFY(1)
    }

    /**
     * delete one resource call
     */
    private fun handleDelete(ind: ResourceRestIndividual){
        val pos = randomness.nextInt(0, ind.getResourceCalls().size - 1)
        ind.removeResourceCall(pos)
    }

    /**
     * swap two resource calls
     */
    private fun handleSwap(ind: ResourceRestIndividual){
        val candidates = randomness.choose(Array(ind.getResourceCalls().size){i -> i}.toList(), 2)
        ind.swapResourceCall(candidates[0], candidates[1])
    }

    /**
     * add new resource call
     *
     * Note that if dependency is enabled,
     * the added resource can be its dependent resource with a probability i.e.,[config.probOfEnablingResourceDependencyHeuristics]
     */
    private fun handleAdd(ind: ResourceRestIndividual){

        val sizeOfCalls = ind.getResourceCalls().size

         var max = config.maxTestSize
        ind.getResourceCalls().forEach { max -= it.actions.size }

        val fromDependency = rm.isDependencyNotEmpty()
                && randomness.nextBoolean(config.probOfEnablingResourceDependencyHeuristics)

        var call = if(fromDependency){
                        rm.handleAddDepResource(ind, max)
                    }else null

        if(call == null){
            call =  rm.handleAddResource(ind, max)
            val pos = randomness.nextInt(0, ind.getResourceCalls().size)
            ind.addResourceCall(pos, call)
        }else{
            rm.bindCallWithFront(call, ind.getResourceCalls().toMutableList())

            //if call is to create new resource, and the related resource is not related to any resource, it might need to put the call in the front of ind,
            //else add last position if it has dependency with existing resources
            val pos = if(ind.getResourceCalls().filter { !it.template.independent }.isNotEmpty())
                ind.getResourceCalls().size
            else
                0

            ind.addResourceCall( pos, call)
        }

        assert(sizeOfCalls == ind.getResourceCalls().size - 1)
    }

    /**
     * replace one of resource call with other resource
     */
    private fun handleReplace(ind: ResourceRestIndividual){
        var max = config.maxTestSize

        ind.getResourceCalls().forEach { max -= it.actions.size }
        val call = rm.handleAddResource(ind, max)

        val pos = randomness.nextInt(0, ind.getResourceCalls().size - 1)
        ind.replaceResourceCall(pos, call)
    }

    /**
     *  modify one of resource call with other template
     */
    private fun handleModify(ind: ResourceRestIndividual){
        val pos = randomness.nextInt(0, ind.getResourceCalls().size-1)
        val old = ind.getResourceCalls()[pos]
        var max = config.maxTestSize
        ind.getResourceCalls().forEach { max -= it.actions.size }
        max += ind.getResourceCalls()[pos].actions.size
        var new = old.resourceInstance.ar.generateAnother(old, randomness, max)
        if(new == null){
            new = old.resourceInstance.ar.sampleOneAction(null, randomness, max)
        }
        assert(new != null)
        ind.replaceResourceCall(pos, new)
    }

    /**
     * for ResourceRestIndividual, dbaction(s) has been determined (e.g., whether involves db, and bind values with actions) when an individual is created
     */
    override fun addInitializingActions(individual: EvaluatedIndividual<*>) {
        //do noting
    }


}