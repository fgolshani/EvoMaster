package org.evomaster.core.problem.rest.resource

import org.evomaster.core.Lazy
import org.evomaster.core.database.DbAction
import org.evomaster.core.database.DbActionUtils
import org.evomaster.core.problem.rest.SampleType
import org.evomaster.core.problem.rest.resource.model.RestResourceCalls
import org.evomaster.core.search.Action
import org.evomaster.core.search.Individual
import org.evomaster.core.search.gene.Gene
import org.evomaster.core.search.gene.GeneUtils
import org.evomaster.core.search.service.Randomness
import org.evomaster.core.search.tracer.TrackOperator
import java.lang.IllegalArgumentException

class ResourceRestIndividual (
        private val resourceCalls: MutableList<RestResourceCalls>,
        val sampleType: SampleType,
        val dbInitialization: MutableList<DbAction> = mutableListOf(),
        trackOperator: TrackOperator? = null,
        traces : MutableList<ResourceRestIndividual>?=null
): Individual (trackOperator, traces){

    override fun copy(): Individual {
        return ResourceRestIndividual(
                resourceCalls.map { it.copy() }.toMutableList(),
                sampleType,
                dbInitialization.map { d -> d.copy() as DbAction } as MutableList<DbAction>
        )
    }

    override fun canMutateStructure(): Boolean {
        return sampleType == SampleType.RANDOM ||
                sampleType == SampleType.SMART_GET_COLLECTION ||
                sampleType == SampleType.SMART_RESOURCE
    }

    override fun seeActions(): List<out Action> = resourceCalls.flatMap { it.actions }

    fun removeResourceCall(position : Int) {
        if(position >= resourceCalls.size)
            throw IllegalArgumentException("position is out of range of list")
        resourceCalls.removeAt(position)
    }

    fun addResourceCall(position: Int, restCalls : RestResourceCalls) {
        if(position > resourceCalls.size)
            throw IllegalArgumentException("position is out of range of list")
        resourceCalls.add(position, restCalls)
    }

    fun replaceResourceCall(position: Int, restCalls: RestResourceCalls){
        if(position > resourceCalls.size)
            throw IllegalArgumentException("position is out of range of list")
        resourceCalls.set(position, restCalls)
    }

    fun swapResourceCall(position1: Int, position2: Int){
        if(position1 > resourceCalls.size || position2 > resourceCalls.size)
            throw IllegalArgumentException("position is out of range of list")
        if(position1 == position2)
            throw IllegalArgumentException("It is not necessary to swap two same position on the resource call list")
        val first = resourceCalls[position1]
        resourceCalls.set(position1, resourceCalls[position2])
        resourceCalls.set(position2, first)
    }

    override fun seeGenesIdMap() : Map<Gene, String>{
        return resourceCalls.flatMap { r -> r.seeGenesIdMap().map { it.key to it.value } }.toMap()
    }

    override fun next(trackOperator: TrackOperator) : ResourceRestIndividual?{
        return ResourceRestIndividual(
                resourceCalls.map { it.copy() }.toMutableList(),
                sampleType,
                dbInitialization.map { d -> d.copy() as DbAction } as MutableList<DbAction>,
                trackOperator,
                if(getTracking() == null) mutableListOf() else getTracking()!!.plus(this).map { (it as ResourceRestIndividual).copy() as ResourceRestIndividual}.toMutableList()
        )
    }

    override fun copy(withTrack: Boolean): ResourceRestIndividual {
        when(withTrack){
            false-> return copy() as ResourceRestIndividual
            else ->{
                getTracking()?:return copy() as ResourceRestIndividual
                return ResourceRestIndividual(
                        resourceCalls.map { it.copy() }.toMutableList(),
                        sampleType,
                        dbInitialization.map { d -> d.copy() as DbAction } as MutableList<DbAction>,
                        trackOperator,
                        getTracking()!!.map { (it as ResourceRestIndividual).copy() as ResourceRestIndividual}.toMutableList()
                )
            }
        }
    }

    fun getResourceCalls() : List<RestResourceCalls> = resourceCalls.toList()

    override fun seeGenes(filter: GeneFilter): List<out Gene> {
        return when (filter) {
            GeneFilter.ALL -> dbInitialization.flatMap(DbAction::seeGenes).plus(seeActions().flatMap(Action::seeGenes))
            GeneFilter.NO_SQL -> seeActions().flatMap(Action::seeGenes)
            GeneFilter.ONLY_SQL -> dbInitialization.flatMap(DbAction::seeGenes)
        }
    }

    override fun size(): Int  = seeActions().size

    //following is same with [RestIndividual]
    override fun verifyInitializationActions(): Boolean {
        return DbActionUtils.verifyActions(dbInitialization.filterIsInstance<DbAction>())
    }

    override fun repairInitializationActions(randomness: Randomness) {
        /**
         * First repair SQL Genes (i.e. SQL Timestamps)
         */
        GeneUtils.repairGenes(this.seeGenes(Individual.GeneFilter.ONLY_SQL).flatMap { it.flatView() })

        /**
         * Now repair database constraints (primary keys, foreign keys, unique fields, etc.)
         */
        if (!verifyInitializationActions()) {
            DbActionUtils.repairBrokenDbActionsList(dbInitialization, randomness)
            Lazy.assert{verifyInitializationActions()}
        }
    }

}