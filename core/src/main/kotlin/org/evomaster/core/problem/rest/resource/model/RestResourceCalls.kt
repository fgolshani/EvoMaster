package org.evomaster.core.problem.rest.resource.model

import org.evomaster.core.database.DbAction
import org.evomaster.core.problem.rest.HttpVerb
import org.evomaster.core.problem.rest.RestAction
import org.evomaster.core.problem.rest.RestCallAction
import org.evomaster.core.search.gene.Gene
import org.evomaster.core.search.impact.ImpactUtil

/**
 * the class is used to structure actions regarding resources.
 * @property template is a resource template, e.g., POST-GET
 * @property resource presents a resource that [actions] perform on. [resource] is an instance of [RestAResource]
 * @property actions is a sequence of actions in the [RestResourceCalls] that follows [template]
 */
class RestResourceCalls(
        val template: CallsTemplate,
        val resource: RestResource,
        val actions: MutableList<RestAction>
){

    /**
     * [doesCompareDB] represents whether to compare db data after executing calls
     */
    var doesCompareDB : Boolean = false

    /**
     * [dbActions] are used to initialize data for rest actions, either select from db or insert new data into db
     */
    val dbActions = mutableListOf<DbAction>()

    var status = ResourceStatus.NOT_FOUND

    fun copy() : RestResourceCalls{
        val copy = RestResourceCalls(template, resource.copy(), actions.map { a -> a.copy() as RestAction}.toMutableList())
        if(dbActions.isNotEmpty()){
            dbActions.forEach { db->
                copy.dbActions.add(db.copy() as DbAction)
            }
        }
        copy.doesCompareDB = doesCompareDB

        return copy
    }

    /**
     * @return genes that represents this resource, i.e., longest action in this resource call
     */
    fun seeGenes() : List<out Gene>{
        return longestPath().seeGenes()
    }

    /**
     * this is used to index genes when identifying its impact.
     * key is Gene,
     * value is id of Gene
     */
    fun seeGenesIdMap() : Map<Gene, String>{
        longestPath().apply {
            return seeGenes().map { it to ImpactUtil.generateId(this, it) }.toMap()
        }
    }

    fun repairGenesAfterMutation(gene: Gene? = null){
        val target = longestPath()
        if(gene != null) repairGenePerAction(gene, target)
        else{
            actions.filter { it is RestCallAction && it != target }
                    .forEach{a-> (a as RestCallAction).bindToSamePathResolution(target as RestCallAction)}
        }
    }

    private fun longestPath() : RestAction{
        val max = actions.filter { it is RestCallAction }.asSequence().map { a -> (a as RestCallAction).path.levels() }.max()!!
        val candidates = actions.filter { a -> a is RestCallAction && a.path.levels() == max }
        return candidates.first()
    }

    private fun repairGenePerAction(gene: Gene, action : RestAction){
        if(gene != null){
            val genes = action.seeGenes().flatMap { g->g.flatView() }
            if(genes.contains(gene))
                genes.filter { ig-> ig != gene && ig.name == gene.name && ig::class.java.simpleName == gene::class.java.simpleName }.forEach {cg->
                    cg.copyValueFrom(gene)
                }
        }
    }

    fun getVerbs(): Array<HttpVerb>{
        return actions.filter { it is RestCallAction }.map { (it as RestCallAction).verb }.toTypedArray()
    }


    enum class ResourceStatus{
        NOT_EXISTING,
        EXISTING,
        CREATED,
        NOT_ENOUGH_LENGTH,
        NOT_FOUND,
        NOT_FOUND_DEPENDENT
    }
}