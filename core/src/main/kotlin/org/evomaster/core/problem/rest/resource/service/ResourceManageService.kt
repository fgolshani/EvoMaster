package org.evomaster.core.problem.rest.resource.service

import com.google.inject.Inject
import org.evomaster.client.java.controller.api.dto.database.operations.DataRowDto
import org.evomaster.core.EMConfig
import org.evomaster.core.database.DbAction
import org.evomaster.core.database.DbActionUtils
import org.evomaster.core.problem.rest.HttpVerb
import org.evomaster.core.problem.rest.RestAction
import org.evomaster.core.problem.rest.RestCallAction
import org.evomaster.core.problem.rest.SampleType
import org.evomaster.core.problem.rest.auth.AuthenticationInfo
import org.evomaster.core.problem.rest.param.BodyParam
import org.evomaster.core.problem.rest.resource.ResourceRestIndividual
import org.evomaster.core.problem.rest.resource.binding.ParamGeneBindMap
import org.evomaster.core.problem.rest.resource.binding.ParamUtil
import org.evomaster.core.problem.rest.resource.util.ResourceTemplateUtil
import org.evomaster.core.problem.rest.resource.model.RestResource
import org.evomaster.core.problem.rest.resource.model.ResourceRestCalls
import org.evomaster.core.problem.rest.resource.model.dependency.SelfResourcesRelation
import org.evomaster.core.problem.rest.resource.parser.ParserUtil
import org.evomaster.core.search.Action
import org.evomaster.core.search.gene.*
import org.evomaster.core.search.service.Randomness
import org.evomaster.core.search.service.Sampler
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * the class is used to manage all resources
 */
class ResourceManageService {

    companion object {
        val log: Logger = LoggerFactory.getLogger(ResourceManageService::class.java)
    }

    @Inject
    private lateinit var sampler: Sampler<*>

    @Inject
    private lateinit var randomness: Randomness

    @Inject
    private lateinit var config: EMConfig

    @Inject
    private lateinit var depAndDbManager: DependencyAndDBManager

    /**
     * key is resource path
     * value is an abstract resource
     */
    private val resourceCluster : MutableMap<String, RestResource> = mutableMapOf()

    fun initRestResources(actionCluster : MutableMap<String, Action>) {
        /*
        init resourceCluster with actions
         */
        actionCluster.values.forEach { u ->
            if (u is RestCallAction) {
                val resource = resourceCluster.getOrPut(u.path.toString()) {
                    RestResource(u.path.copy(), mutableMapOf())
                }
                resource.addMethod(u)
            }
        }
        /*
         set ancestors for each resource
         */
        resourceCluster.values.forEach{it.initAncestors(getResourceCluster().values.toList())}

        /*
         init every resource regarding i.e., what kind of actions, how to prepare the resource
         */
        resourceCluster.values.forEach{it.init(config.doesApplyTokenParser)}

        if(hasDBHandler()){
            /*
                derive possible db creation for each abstract resources.
                The derived db creation needs to be further confirmed based on feedback from evomaster driver (NOT IMPLEMENTED YET)
             */
            (sampler as ResourceRestSampler).sqlInsertBuilder?.let {
                depAndDbManager.initTableInfo(it)
            }
            if(config.doesApplyTokenParser){
                depAndDbManager.deriveResourceToTable(resourceCluster.values.toMutableList())
            }
        }

        if(config.doesApplyTokenParser && config.probOfEnablingResourceDependencyHeuristics > 0.0)
            depAndDbManager.initDependency(resourceCluster.values.toMutableList())

    }

    /**
     * this function is used to initialized ad-hoc individuals
     */
    fun createAdHocIndividuals(auth: AuthenticationInfo, adHocInitialIndividuals : MutableList<ResourceRestIndividual>){
        val sortedResources = resourceCluster.values.sortedByDescending { it.path.levels() }.asSequence()

        //GET, PATCH, DELETE
        sortedResources.forEach { ar->
            ar.getMethods().filter { it is RestCallAction && it.verb != HttpVerb.POST && it.verb != HttpVerb.PUT }.forEach {a->
                val call = ar.sampleOneAction(a.copy() as RestAction, randomness, config.maxTestSize)
                call.actions.forEach {a->
                    if(a is RestCallAction) a.auth = auth
                }
                adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
            }
        }

        //all POST with one post action
        sortedResources.forEach { ar->
            ar.getMethods().filter { it is RestCallAction && it.verb == HttpVerb.POST}.forEach { a->
                val call = ar.sampleOneAction(a.copy() as RestAction, randomness, config.maxTestSize)
                call.actions.forEach { (it as RestCallAction).auth = auth }
                adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
            }
        }

        sortedResources
                .filter { it.getMethods().find { a -> a is RestCallAction && a.verb == HttpVerb.POST } != null && it.postCreation.actions.size > 1   }
                .forEach { ar->
                    ar.genPostChain(randomness, config.maxTestSize)?.let {call->
                        call.actions.forEach { (it as RestCallAction).auth = auth }
                        adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
                    }
                }

        //PUT
        sortedResources.forEach { ar->
            ar.getMethods().filter { it is RestCallAction && it.verb == HttpVerb.PUT }.forEach {a->
                val call = ar.sampleOneAction(a.copy() as RestAction, randomness)
                call.actions.forEach { (it as RestCallAction).auth = auth }
                adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
            }
        }

        //template
        sortedResources.forEach { ar->
            ar.templates.values.filter { t-> t.template.contains(ResourceTemplateUtil.SeparatorTemplate) }
                    .forEach {ct->
                        val call = ar.sampleRestResourceCalls(ct.template, randomness, config.maxTestSize)
                        call.actions.forEach { if(it is RestCallAction) it.auth = auth }
                        adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
                    }
        }

    }

    fun isDependencyNotEmpty() : Boolean{
        return depAndDbManager.dependencies.isNotEmpty()
    }

    /************************  manage to resource call regarding dependency ***********************************/

    fun handleAddDepResource(ind : ResourceRestIndividual, maxTestSize : Int, candidates : MutableList<ResourceRestCalls> = mutableListOf()) : Pair<ResourceRestCalls?, ResourceRestCalls>?{
        //return handleAddDepResource(ind.getResourceCalls().subList(afterPosition+1, ind.getResourceCalls().size).toMutableList(), maxTestSize)
        val options = mutableListOf(0, 1)
        while (options.isNotEmpty()){
            val option = randomness.choose(options)
            val pair = when(option){
                0 -> handleAddNewDepResource(if (candidates.isEmpty()) ind.getResourceCalls().toMutableList() else candidates, maxTestSize)
                1 -> handleAddNotCheckedDepResource(ind, maxTestSize)
                else -> null
            }
            if(pair != null) return pair
            options.remove(option)
        }
        return null
    }

    /**
     * @return pair, first is an existing resource call in [sequence], and second is a newly created resource call that is related to the first
     */
    private fun handleAddNewDepResource(sequence: MutableList<ResourceRestCalls>, maxTestSize : Int) : Pair<ResourceRestCalls?, ResourceRestCalls>?{

        val existingRs = sequence.map { it.resourceInstance.getAResourceKey() }

        val candidates = sequence
                .filter {
                    depAndDbManager.dependencies[it.resourceInstance.getAResourceKey()] != null &&
                            depAndDbManager.dependencies[it.resourceInstance.getAResourceKey()]!!.any { dep ->
                                dep.targets.any { t -> existingRs.none {  e -> e == t }  } ||
                                        (dep is SelfResourcesRelation && existingRs.count { e -> e == it.resourceInstance.getAResourceKey() } == 1)
                            }
                }

        if(candidates.isNotEmpty()){
            val first = randomness.choose(candidates)
            /*
                add self relation with a relative low probability, i.e., 20%
             */
            depAndDbManager.dependencies[first.resourceInstance.getAResourceKey()]!!.flatMap {
                dep-> if(dep !is SelfResourcesRelation) dep.targets.filter {  !existingRs.contains(it) } else if(randomness.nextBoolean(0.2)) dep.targets else mutableListOf()
            }.let { templates->
                if(templates.isNotEmpty()){
                    resourceCluster[randomness.choose(templates)]!!.sampleAnyRestResourceCalls(randomness,maxTestSize )?.let {second->
                        return Pair(first, second)
                    }
                }
            }
        }
        return null
    }

    private fun handleAddNotCheckedDepResource(ind: ResourceRestIndividual, maxTestSize : Int) : Pair<ResourceRestCalls?, ResourceRestCalls>?{
        val checked = ind.getResourceCalls().flatMap {cur->
            depAndDbManager.findDependentResources(ind, cur).plus(depAndDbManager.findNonDependentResources(ind, cur))
        }.map { it.resourceInstance.getAResourceKey() }.toHashSet()

        resourceCluster.keys.filter { !checked.contains(it) }.let { templates->
            if(templates.isNotEmpty()){
                resourceCluster[randomness.choose(templates)]!!.sampleAnyRestResourceCalls(randomness,maxTestSize )?.let {second->
                    return Pair(null, second)
                }
            }
        }
        return null
    }

    fun handleDelNonDepResource(ind: ResourceRestIndividual) : ResourceRestCalls?{
        val candidates = ind.getResourceCalls().filter {cur->
            !depAndDbManager.existsDependentResources(ind, cur) && cur.isDeletable
        }
        if (candidates.isEmpty()) return null

        candidates.filter { depAndDbManager.isNonDepResources(ind, it) }.apply {
            if(isNotEmpty())
                return randomness.choose(this)
            else
                return randomness.choose(candidates)
        }
    }


    fun handleSwapDepResource(ind: ResourceRestIndividual): Pair<Int, Int>?{
        val options = mutableListOf(1,2,3)
        while (options.isNotEmpty()){
            val option = randomness.choose(options)
            val pair = when(option){
                1 -> adjustDepResource(ind)
                2 -> swapNotConfirmedDepResource(ind)
                3 -> swapNotCheckedResource(ind)
                else -> null
            }
            if(pair != null) return pair
            options.remove(option)
        }
        return null
    }

    private fun adjustDepResource(ind: ResourceRestIndividual): Pair<Int, Int>?{
        val candidates = mutableMapOf<Int, MutableSet<Int>>()
        ind.getResourceCalls().forEachIndexed { index, cur ->
            depAndDbManager.findDependentResources(ind, cur, minProbability = ParserUtil.SimilarityThreshold).map { ind.getResourceCalls().indexOf(it) }.filter { second -> index < second }.apply {
                if(isNotEmpty()) candidates.getOrPut(index){ mutableSetOf()}.addAll(this.toHashSet())
            }
        }
        if(candidates.isNotEmpty()) randomness.choose(candidates.keys).let {
            return Pair(it, randomness.choose(candidates.getValue(it)))
        }
        return null
    }

    private fun swapNotConfirmedDepResource(ind: ResourceRestIndividual): Pair<Int, Int>?{
        val probCandidates = ind.getResourceCalls().filter { depAndDbManager.existsDependentResources(ind, it, maxProbability = ParserUtil.SimilarityThreshold) }
        if (probCandidates.isEmpty()) return null
        val first = randomness.choose(probCandidates)
        val second = randomness.choose(depAndDbManager.findDependentResources(ind, first, maxProbability = ParserUtil.SimilarityThreshold))
        return Pair(ind.getResourceCalls().indexOf(first), ind.getResourceCalls().indexOf(second))
    }

    private fun swapNotCheckedResource(ind: ResourceRestIndividual) : Pair<Int, Int>?{
        val candidates = mutableMapOf<Int, MutableSet<Int>>()
        ind.getResourceCalls().forEachIndexed { index, cur ->
            val checked = depAndDbManager.findDependentResources(ind, cur).plus(depAndDbManager.findNonDependentResources(ind, cur))
            ind.getResourceCalls().filter { it != cur && !checked.contains(it) }.map { ind.getResourceCalls().indexOf(it) }.apply {
                if(isNotEmpty()) candidates.getOrPut(index){ mutableSetOf()}.addAll(this)
            }
        }
        if(candidates.isNotEmpty()) randomness.choose(candidates.keys).let {
            return Pair(it, randomness.choose(candidates.getValue(it)))
        }
        return null
    }

    fun handleAddResource(ind : ResourceRestIndividual, maxTestSize : Int) : ResourceRestCalls{
        val existingRs = ind.getResourceCalls().map { it.resourceInstance.getAResourceKey() }
        var candidate = randomness.choose(getResourceCluster().filterNot { r-> existingRs.contains(r.key) }.keys)
        return resourceCluster[candidate]!!.sampleAnyRestResourceCalls(randomness,maxTestSize )
    }

    /************************  sample resource individual regarding dependency ***********************************/
    /**
     *  if involved db, there may a problem to solve,
     *  e.g., an individual "ABCDE",
     *  "B" and "C" are mutual, which means that they are related to same table, "B" -> Tables TAB1, TAB2, and "C" -> Tables TAB2, TAB3
     *  in order to create resources for "B", we insert an row in TAB1 and an row in TAB2, but TAB1 and TAB2 may refer to other tables, so we also need to insert relative
     *  rows in reference tables,
     *  1. if TAB1 and TAB2 do not share any same reference tables, it is simple, just insert row with random values
     *  2. if TAB1 and TAB2 share same reference tables, we may need to remove duplicated insertions
     */
    fun sampleRelatedResources(calls : MutableList<ResourceRestCalls>, sizeOfResource : Int, maxSize : Int) {
        var start = - calls.sumBy { it.actions.size }

        /*
            TODO strategy to select a dependency
         */
        val first = randomness.choose(depAndDbManager.dependencies.keys)
        sampleCall(first, true, calls, maxSize)
        var sampleSize = 1
        var size = calls.sumBy { it.actions.size } + start
        val excluded = mutableListOf<String>()
        val relatedResources = mutableListOf<ResourceRestCalls>()
        excluded.add(first)
        relatedResources.add(calls.last())

        while (sampleSize < sizeOfResource && size < maxSize){
            val candidates = depAndDbManager.dependencies[first]!!.flatMap { it.targets as MutableList<String> }.filter { !excluded.contains(it) }
            if(candidates.isEmpty())
                break

            val related = randomness.choose(candidates)
            excluded.add(related)
            sampleCall(related, true, calls, size, false, if(related.isEmpty()) null else relatedResources)
            relatedResources.add(calls.last())
            size = calls.sumBy { it.actions.size } + start
        }
    }

    private fun existRelatedTable(call: ResourceRestCalls) : Boolean{
        if(!call.template.independent) return false
        call.actions.filter { it is RestCallAction }.any { !getRelatedTablesByAction(it as RestCallAction) }.apply {
            if(this) return true
        }
        call.actions.filter { it is RestCallAction }.find { resourceCluster[(it as RestCallAction).path.toString()]?.resourceToTable?.paramToTable?.isNotEmpty()?:false}.apply {
            if(this != null) return true
        }
        return false
    }

    private fun getRelatedTablesByAction(action: RestCallAction): Boolean{
        val paramInfos = getRestResource(action.path.toString())?.paramsInfo?.filter { p->p.value.involvedAction.contains(action.verb) }?:return false
        if(paramInfos.isEmpty()) return false
        val ar = getRestResource(action.path.toString())!!.resourceToTable
        return ar.paramToTable.any {p->
            paramInfos.any{ r ->
                r.key == p.key
            }
        }
    }

    /************************  sample individual regarding resources ***********************************/

    /**
     * @param doesCreateResource presents whether to create an resource for the call, the resource can be created by either POST action or INSERT Sql
     */
    fun sampleCall(
            resourceKey: String,
            doesCreateResource: Boolean,
            calls : MutableList<ResourceRestCalls>,
            size : Int,
            forceInsert: Boolean = false,
            bindWith : MutableList<ResourceRestCalls>? = null
    ){
        val ar = resourceCluster[resourceKey]
                ?: throw IllegalArgumentException("resource path $resourceKey does not exist!")

        if(!doesCreateResource ){
            val call = ar.sampleIndResourceCall(randomness,size)
            calls.add(call)
//            if(hasDBHandler() && call.template.template == HttpVerb.GET.toString() && randomness.nextBoolean(0.5)){
//                //val created = handleCallWithDBAction(ar, call, false, true)
//                generateDbActionForCall(call, forceInsert = false, forceSelect = true)
//            }
            return
        }

        assert(!ar.isIndependent())
        var candidateForInsertion : String? = null

        if(hasDBHandler()
                && ar.resourceToTable.paramToTable.isNotEmpty()
                && (if(forceInsert) forceInsert else randomness.nextBoolean(0.5))){

            val candidates = ar.templates.filter { it.value.independent }
            candidateForInsertion = if(candidates.isNotEmpty()) randomness.choose(candidates.keys) else null
        }

        val candidate = if(candidateForInsertion.isNullOrBlank()) {
            //prior to select the template with POST
            ar.templates.filter { !it.value.independent }.run {
                if(isNotEmpty())
                    randomness.choose(this.keys)
                else
                    randomness.choose(ar.templates.keys)
            }
        } else candidateForInsertion

        val call = ar.genCalls(candidate,randomness,size,true,true)
        calls.add(call)

        if(hasDBHandler()){
            if(call.status != ResourceRestCalls.ResourceStatus.CREATED
                    || existRelatedTable(call)
                    || candidateForInsertion != null){

                /*
                    derive possible db, and bind value according to db
                */
                //val created = handleCallWithDBAction(ar, call, forceInsert, false)
                val created = generateDbActionForCall(call, forceInsert, forceSelect = false)
                if(!created){
                    //TODO MAN record the call when postCreation fails
                }
            }
        }

        if(bindWith != null){
            bindCallWithFront(call, bindWith)
        }
    }

    /************************  handling resource individual with db ***********************************/
    /**
     * generate dbaction for call
     */
    private fun generateDbActionForCall(call: ResourceRestCalls, forceInsert: Boolean, forceSelect: Boolean) : Boolean{

        val candidates = depAndDbManager.extractRelatedTableForActions(call.actions)
        val relatedTables = candidates.values.flatMap { it.map { bind->bind.tableName.toLowerCase() } }.toHashSet()

        val dbActions = mutableListOf<DbAction>()

        var failToLinkWithResource = false

        relatedTables.reversed().forEach { tableName->
            if(forceInsert){
                generateInserSql(tableName, dbActions)
            }else if(forceSelect){
                if(depAndDbManager.getRowInDataInDB(tableName) != null && depAndDbManager.getRowInDataInDB(tableName)!!.isNotEmpty()) generateSelectSql(tableName, dbActions)
                else failToLinkWithResource = true
            }else{
                if(depAndDbManager.getRowInDataInDB(tableName)!= null ){
                    val size = depAndDbManager.getRowInDataInDB(tableName)!!.size
                    when{
                        size < config.minRowOfTable -> generateInserSql(tableName, dbActions).apply {
                            failToLinkWithResource = failToLinkWithResource || !this
                        }
                        else ->{
                            if(randomness.nextBoolean(config.probOfSelectFromDB)){
                                generateSelectSql(tableName, dbActions)
                            }else{
                                generateInserSql(tableName, dbActions).apply {
                                    failToLinkWithResource = failToLinkWithResource || !this
                                }
                            }
                        }
                    }
                }else
                    failToLinkWithResource = true
            }
        }

        if(dbActions.isNotEmpty()){
            (0 until (dbActions.size - 1)).forEach { i ->
                (i+1 until dbActions.size).forEach { j ->
                    dbActions[i].table.foreignKeys.any { f->f.targetTable == dbActions[j].table.name}.let {
                        if(it){
                            val idb = dbActions[i]
                            dbActions[i] = dbActions[j]
                            dbActions[j] = idb
                        }
                    }
                }
            }
            DbActionUtils.randomizeDbActionGenes(dbActions, randomness)
            repairDbActions(dbActions)

            val removedDbAction = mutableListOf<DbAction>()

            dbActions.map { it.table.name }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach {tableName->
                removedDbAction.addAll(dbActions.filter { it.table.name == tableName }.run { this.subList(1, this.size) })
            }

            if(removedDbAction.isNotEmpty()){
                dbActions.removeAll(removedDbAction)

                val previous = mutableListOf<DbAction>()
                dbActions.forEachIndexed { index, dbAction ->
                    if(index != 0 && dbAction.table.foreignKeys.isNotEmpty() && dbAction.table.foreignKeys.find { fk -> removedDbAction.find { it.table.name == fk.targetTable } !=null } != null)
                        DbActionUtils.repairFK(dbAction, previous)
                    previous.add(dbAction)
                }
            }

            /*
             TODO bind data according to action or dbaction?

             Note that since we prepare data for rest actions, we bind values of dbaction based on rest actions.

             */
            if(relatedTables.any { !dbActions.any { d->d.table.name.toLowerCase() == it.toLowerCase() } }){
                println("------------------------")
            }
            bindCallWithDBAction(call, dbActions, candidates)

            call.dbActions.addAll(dbActions)
        }
        return relatedTables.isNotEmpty() && !failToLinkWithResource
    }


    private fun generateSelectSql(tableName : String, dbActions: MutableList<DbAction>, forceDifferent: Boolean = false, withDbAction: DbAction?=null){
        if(dbActions.map { it.table.name }.contains(tableName)) return

        assert(depAndDbManager.getRowInDataInDB(tableName) != null && depAndDbManager.getRowInDataInDB(tableName)!!.isNotEmpty())
        assert(!forceDifferent || withDbAction == null)

        val columns = if(forceDifferent && withDbAction!!.representExistingData){
            selectToDataRowDto(withDbAction, tableName)
        }else {
            randomness.choose(depAndDbManager.getRowInDataInDB(tableName)!!)
        }

        val selectDbAction = (sampler as ResourceRestSampler).sqlInsertBuilder!!.extractExistingByCols(tableName, columns)
        dbActions.add(selectDbAction)
    }

    private fun generateInserSql(tableName : String, dbActions: MutableList<DbAction>) : Boolean{
        val insertDbAction =
                (sampler as ResourceRestSampler).sqlInsertBuilder!!
                        .createSqlInsertionActionWithAllColumn(tableName)

        if(insertDbAction.isEmpty())
            return false

        dbActions.addAll(insertDbAction)
        return true
    }


    private fun selectToDataRowDto(dbAction : DbAction, tableName : String) : DataRowDto{
        dbAction.seeGenes().forEach { assert((it is SqlPrimaryKeyGene || it is ImmutableDataHolderGene || it is SqlForeignKeyGene)) }
        val set = dbAction.seeGenes().filter { it is SqlPrimaryKeyGene }.map { ((it as SqlPrimaryKeyGene).gene as ImmutableDataHolderGene).value }.toSet()
        return randomness.choose(depAndDbManager.getRowInDataInDB(tableName)!!.filter { it.columnData.toSet().equals(set) })
    }


    /************************  bind/repair values of resource individual ***********************************/

    private fun bindCallWithDBAction(
            call: ResourceRestCalls,
            dbActions: MutableList<DbAction>,
            candidates: MutableMap<RestAction, MutableList<ParamGeneBindMap>>,
            forceBindParamBasedOnDB : Boolean = false){
        assert(call.actions.isNotEmpty())
        for (a in call.actions){
            if(a is RestCallAction){
                var list = candidates[a]
                if (list == null) list = candidates.filter { a.getName() == it.key.getName() }.values.run {
                    if(this.isEmpty()) null else this.first()
                }
                if(list!= null && list.isNotEmpty()){
                    list.forEach { pToGene->
                        var dbAction = dbActions.find { it.table.name.toLowerCase() == pToGene.tableName.toLowerCase() }
                                ?: throw IllegalArgumentException("cannot find ${pToGene.tableName} in db actions ${dbActions.map { it.table.name }.toTypedArray()}")
                        var columngene = dbAction?.seeGenes().first { g-> g.name.toLowerCase() == pToGene.column.toLowerCase() }
                        if(dbAction!= null && columngene!=null){
                            val param = a.parameters.find { p-> getRestResource(a.path.toString())!!.getParamId(a.parameters, p).toLowerCase() == pToGene.paramId.toLowerCase() }
                            param?.let {
                                if(pToGene.isElementOfParam){
                                    if(param is BodyParam && param.gene is ObjectGene){
                                        param.gene.fields.find { f-> f.name == pToGene.targetToBind }?.let { paramGene->
                                            ParamUtil.bindParamWithDbAction(columngene, paramGene, forceBindParamBasedOnDB || dbAction.representExistingData)
                                        }
                                    }
                                }else{
                                    ParamUtil.bindParamWithDbAction(columngene, param.gene, forceBindParamBasedOnDB || dbAction.representExistingData)
                                }
                            }
                        }

                    }
                }
            }
        }
    }

    private fun bindCallWithOtherDBAction(call : ResourceRestCalls, dbActions: MutableList<DbAction>){
        val dbRelatedToTables = dbActions.map { it.table.name }.toMutableList()
        val dbTables = call.dbActions.map { it.table.name }.toMutableList()

        if(dbRelatedToTables.containsAll(dbTables)){
            call.dbActions.clear()
        }else{
            call.dbActions.removeIf { dbRelatedToTables.contains(it.table.name) }
            /*
             TODO Man there may need to add selection in order to ensure the reference pk exists
             */
            //val selections = mutableListOf<DbAction>()
            val previous = mutableListOf<DbAction>()
            call.dbActions.forEach {dbaction->
                if(dbaction.table.foreignKeys.find { dbRelatedToTables.contains(it.targetTable) }!=null){
                    val refers = DbActionUtils.repairFK(dbaction, dbActions.plus(previous).toMutableList())
                    //selections.addAll( (sampler as ResourceRestSampler).sqlInsertBuilder!!.generateSelect(refers) )
                }
                previous.add(dbaction)
            }
            repairDbActions(dbActions.plus(call.dbActions).toMutableList())
            //call.dbActions.addAll(0, selections)
        }

        val dbActions = dbActions.plus(call.dbActions).toMutableList()
        val candidates = depAndDbManager.extractRelatedTableForActions(call.actions, dbActions)

        bindCallWithDBAction(call, dbActions, candidates, forceBindParamBasedOnDB = true)

    }

    fun bindCallWithFront(call: ResourceRestCalls, front : MutableList<ResourceRestCalls>){

        val targets = front.flatMap { it.actions.filter {a -> a is RestCallAction }}

        /*
        TODO

         e.g., A/{a}, A/{a}/B/{b}, A/{a}/C/{c}
         if there are A/{a} and A/{a}/B/{b} that exists in the test,
         1) when appending A/{a}/C/{c}, A/{a} should not be created again;
         2) But when appending A/{a} in the test, A/{a} with new values should be created.
        */
//        if(call.actions.size > 1){
//            call.actions.removeIf {action->
//                action is RestCallAction &&
//                        //(action.verb == HttpVerb.POST || action.verb == HttpVerb.PUT) &&
//                        action.verb == HttpVerb.POST &&
//                        action != call.actions.last() &&
//                        targets.find {it is RestCallAction && it.getName() == action.getName()}.also {
//                            it?.let {ra->
//                                front.find { call-> call.actions.contains(ra) }?.let { call -> call.isStructureMutable = false }
//                                if(action.saveLocation) (ra as RestCallAction).saveLocation = true
//                                action.locationId?.let {
//                                    (ra as RestCallAction).saveLocation = action.saveLocation
//                                }
//                            }
//                        }!=null
//            }
//        }

        /*
         bind values based front actions,
         */
        call.actions
                .filter { it is RestCallAction }
                .forEach { a ->
                    (a as RestCallAction).parameters.forEach { p->
                        targets.forEach { ta->
                            ParamUtil.bindParam(p, a.path, (ta as RestCallAction).path, ta.parameters)
                        }
                    }
                }

        /*
         bind values of dbactions based front dbactions
         */
        front.flatMap { it.dbActions }.apply {
            if(isNotEmpty())
                bindCallWithOtherDBAction(call, this.toMutableList())
        }

        val frontTables = front.map { Pair(it, it.dbActions.map { it.table.name })}.toMap()
        call.dbActions.forEach { db->
            db.table.foreignKeys.map { it.targetTable }.let {ftables->
                frontTables.filter { entry ->
                    entry.value.intersect(ftables).isNotEmpty()
                }.forEach { t, u ->
                    t.isDeletable = false
                    t.shouldBefore.add(call.resourceInstance.getAResourceKey())
                }
            }
        }
    }

    /**
     *  repair dbaction of resource call after standard mutation
     *  Since standard mutation does not change structure of a test, the involved tables
     *  should be same with previous.
     */
    fun repairRestResourceCalls(call: ResourceRestCalls) {
        call.repairGenesAfterMutation()

        if(hasDBHandler() && call.dbActions.isNotEmpty()){

            val previous = call.dbActions.map { it.table.name }
            call.dbActions.clear()
            //handleCallWithDBAction(ar, call, true, false)
            generateDbActionForCall(call, forceInsert = true, forceSelect = false)

            if(call.dbActions.size != previous.size){
                //remove additions
                call.dbActions.removeIf {
                    !previous.contains(it.table.name)
                }
            }
        }
    }


    /**
     * copy code
     */
    private fun repairDbActions(dbActions: MutableList<DbAction>){
        /**
         * First repair SQL Genes (i.e. SQL Timestamps)
         */
        GeneUtils.repairGenes(dbActions.flatMap { it.seeGenes() })

        /**
         * Now repair database constraints (primary keys, foreign keys, unique fields, etc.)
         */
        DbActionUtils.repairBrokenDbActionsList(dbActions, randomness)
    }


    /************************  utility ***********************************/

    fun getRestResource(resource: String) : RestResource? =resourceCluster[resource]


    fun getResourceCluster() : Map<String, RestResource> {
        return resourceCluster.toMap()
    }
    fun onlyIndependentResource() : Boolean {
        return resourceCluster.values.filter{ r -> !r.isIndependent() }.isEmpty()
    }

    private fun hasDBHandler() : Boolean = sampler is ResourceRestSampler && (sampler as ResourceRestSampler).sqlInsertBuilder!= null && config.doesInvolveDB


}