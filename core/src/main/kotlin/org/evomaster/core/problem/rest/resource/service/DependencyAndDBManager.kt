package org.evomaster.core.problem.rest.resource.service

import com.google.inject.Inject
import org.evomaster.client.java.controller.api.dto.TestResultsDto
import org.evomaster.client.java.controller.api.dto.database.execution.ExecutionDto
import org.evomaster.client.java.controller.api.dto.database.operations.DataRowDto
import org.evomaster.core.database.DbAction
import org.evomaster.core.database.SqlInsertBuilder
import org.evomaster.core.database.schema.Table
import org.evomaster.core.problem.rest.HttpVerb
import org.evomaster.core.problem.rest.RestAction
import org.evomaster.core.problem.rest.RestCallAction
import org.evomaster.core.problem.rest.param.BodyParam
import org.evomaster.core.problem.rest.resource.ResourceRestIndividual
import org.evomaster.core.problem.rest.resource.binding.ParamGeneBindMap
import org.evomaster.core.problem.rest.resource.binding.ParamUtil
import org.evomaster.core.problem.rest.resource.model.ResourceRestCalls
import org.evomaster.core.problem.rest.resource.model.RestResource
import org.evomaster.core.problem.rest.resource.model.dependency.*
import org.evomaster.core.problem.rest.resource.parser.MatchedInfo
import org.evomaster.core.problem.rest.resource.parser.ParserUtil
import org.evomaster.core.problem.rest.resource.util.*
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.gene.ObjectGene
import org.evomaster.core.search.service.Randomness
import kotlin.math.max

class DependencyAndDBManager {

    @Inject
    private lateinit var randomness: Randomness

    @Inject
    private lateinit var rm: ResourceManageService

    /**
     * key is table name
     * value is a list of existing data of PKs in DB
     */
    private val dataInDB : MutableMap<String, MutableList<DataRowDto>> = mutableMapOf()

    /**
     * key is either a path of one resource, or a list of paths of resources
     * value is a list of related to resources
     */
    val dependencies : MutableMap<String, MutableList<ResourceRelatedToResources>> = mutableMapOf()

    /**
     * key is a path of an resource
     * value is a set of resources that is not related to the key, i.e., the key does not rely on
     */
    private val nondependencies : MutableMap<String, MutableSet<String>> = mutableMapOf()

    private val tables = mutableMapOf<String, Table>()

//    private var allParamInfoUpdated = false

    fun initTableInfo(sql : SqlInsertBuilder){
        sql.extractExistingPKs(dataInDB, tables)
    }

    fun initDependency(resourceCluster: MutableList<RestResource>){
        initDependencyBasedOnDerivedTables(resourceCluster)
        deriveDependencyBasedOnSchema(resourceCluster)
    }

    /************************  manage to bind actions with table ***********************************/

    fun extractRelatedTableForActions(actions: List<RestAction>, dbAction: List<DbAction> = mutableListOf()) : MutableMap<RestAction, MutableList<ParamGeneBindMap>>{
        val result = mutableMapOf<RestAction, MutableList<ParamGeneBindMap>>()

        val resourcesMap = mutableMapOf<RestResource, MutableSet<String>>()
        val actionMap = mutableMapOf<RestAction, MutableSet<String>>()
        actions.forEach {
            if(it is RestCallAction){
                val ar = rm.getRestResource(it.path.toString()) ?: throw IllegalArgumentException("cannot find resource ${it.path} for action ${it.getName()}")
                val paramIdSets = resourcesMap.getOrPut(ar){ mutableSetOf()}
                val paramIdSetForAction = actionMap.getOrPut(it){ mutableSetOf()}
                it.parameters.forEach { p->
                    paramIdSets.add(ar.getParamId(it.parameters, p))
                    paramIdSetForAction.add(ar.getParamId(it.parameters, p))
                }
            }
        }

        val relatedTables = dbAction.map { it.table.name }.toHashSet()

        resourcesMap.forEach { resource, paramIdSets ->
            val list = if(relatedTables.isEmpty()) getBindMap(paramIdSets, resource.resourceToTable) else getBindMap(paramIdSets, resource.resourceToTable, relatedTables)
            if(list.isNotEmpty()){
                val cleanList = mutableListOf<ParamGeneBindMap>()
                list.forEach { p->
                    if(!cleanList.any { e->e.equalWith(p)}) cleanList.add(p)
                }
                actions.filter { it is RestCallAction  && it.path.toString() == resource.getName()}.forEach { a->
                    result.put(a, cleanList.filter { l->actionMap[a]!!.contains(l.paramId) }.toMutableList())
                }
            }
        }

        return result
    }


    private fun getBindMap(paramIds : Set<String>, resourceToTable: ResourceRelatedToTable) : MutableList<ParamGeneBindMap>{
        val result = mutableListOf<ParamGeneBindMap>()
        paramIds.forEach { p->
            resourceToTable.paramToTable[p]?.let {pToTable->
                var tables = resourceToTable.getConfirmedDirectTables()
                var found = false
                if(tables.isNotEmpty()){
                    found = getBindMap(p, pToTable, tables, resourceToTable, result)
                }
                tables = resourceToTable.getTablesInDerivedMap()
                if(tables.isNotEmpty())
                    found = getBindMap(p, pToTable, tables, resourceToTable, result)

                if(!found){
                    //cannot bind this paramid with table
                }

            }
        }
        return result
    }

    private fun getBindMap(paramIds : Set<String>, resourceToTable: ResourceRelatedToTable, tables: Set<String>) : MutableList<ParamGeneBindMap>{
        val result = mutableListOf<ParamGeneBindMap>()
        paramIds.forEach { p->
            resourceToTable.paramToTable[p]?.let {pToTable->
                getBindMap(p, pToTable, tables, resourceToTable, result)
            }
        }
        return result
    }


    private fun getBindMap(paramId: String, pToTable : ParamRelatedToTable, tables : Set<String>, resourceToTable: ResourceRelatedToTable, result :  MutableList<ParamGeneBindMap>) : Boolean{
        if(pToTable is SimpleParamRelatedToTable){
            resourceToTable.findBestTableForParam(tables, pToTable)?.let {pair->
                var target = randomness.choose(pair.first)
                val column = resourceToTable.getSimpleParamToSpecifiedTable(target, pToTable)!!.second
                result.add(ParamGeneBindMap(paramId, false, pToTable.referParam.name, tableName = target, column = column))
                return true
            }
        }else if(pToTable is BodyParamRelatedToTable){
            resourceToTable.findBestTableForParam(tables, pToTable)?.let {pair->
                val vote = pair.values.flatMap { it.first }.toMutableSet().map { Pair(it, 0) }.toMap().toMutableMap()

                pair.forEach { f, bestSet ->
                    bestSet.first.forEach { t->
                        vote.replace(t, vote[t]!!+1)
                    }
                }

                pair.forEach { f, bestSet ->
                    val target = if (bestSet.first.size == 1) bestSet.first.first() else bestSet.first.asSequence().sortedBy { vote[it] }.last()
                    val column = resourceToTable.getBodyParamToSpecifiedTable(target, pToTable, f)!!.second.second
                    result.add(ParamGeneBindMap(paramId, true, f, tableName = target, column = column))
                }

                return true
            }
        }
        return false
    }



    /************************  manage relationship between resource and tables ***********************************/

    /**
     * update relationship between resource and tables.
     * Note that the entry point is on the rest fitness.
     */
    fun updateResourceTables(resourceRestIndividual: ResourceRestIndividual, dto : TestResultsDto){

        //updateParamInfo(resourceRestIndividual)

        /*
        TODO how to decide to remove relationship between resource and table
         */
        val addedMap = mutableMapOf<String, MutableSet<String>>()
        val removedMap = mutableMapOf<String, MutableSet<String>>()

        resourceRestIndividual.seeActions().forEachIndexed { index, action ->
            if(action is RestAction) updateParamInfo(action)
            // size of extraHeuristics might be less than size of action due to failure of handling rest action
            if(index < dto.extraHeuristics.size){
                val dbDto = dto.extraHeuristics[index].databaseExecutionDto
                if(action is RestCallAction)
                    updateResourceToTable(action, dbDto, addedMap, removedMap)
            }
        }
        if(addedMap.isNotEmpty() || removedMap.isNotEmpty())
            updateDependencyOnceResourceTableUpdate(addedMap, removedMap)

    }

    private fun updateParamInfo(action: RestAction){
        if(action is RestCallAction){
            val r = rm.getRestResource(action.path.toString())!!
            val additionalInfo = r.updateAdditionalParams(action)
//            allParamInfoUpdated()
            if(!additionalInfo.isNullOrEmpty()){
                deriveParamsToTable(additionalInfo, r)
            }
        }
    }

//    private fun allParamInfoUpdated() {
//        allParamInfoUpdated = rm.getResourceCluster().values.none { !it.allParamsInfoUpdate() }
//    }

    private fun updateDependencyOnceResourceTableUpdate(addedMap: MutableMap<String, MutableSet<String>>, removedMap: MutableMap<String, MutableSet<String>>){

        val groupTable = addedMap.flatMap { it.value }.toHashSet()
        groupTable.forEach { table->
            val relatedResource = addedMap.filter { it.value.contains(table) }.keys

            var find = false
            dependencies.values.forEach {  rlist ->
                rlist.forEach { mu->
                    if(mu is MutualResourcesRelations && mu.targets.containsAll(relatedResource)){
                        mu.referredTables.add(table)
                        find = true
                    }
                }
            }

            if(!find){
                val updatedMutual = mutableListOf<MutualResourcesRelations>()
                dependencies.values.forEach {  rlist ->
                    rlist.forEach { mu->
                        if(mu is MutualResourcesRelations && mu.targets.any { t-> relatedResource.contains(t as String) } && mu.referredTables.contains(table)){
                            updatedMutual.add(mu)
                        }
                    }
                }
                if(updatedMutual.isNotEmpty()){
                    updatedMutual.forEach { mu->
                        val previousResource = (mu.targets as MutableList<String>)
                        val newTargetSet = previousResource.plus(relatedResource).toHashSet().toList()
                        val newMut = MutualResourcesRelations(newTargetSet, 1.0, mu.targets.plus(table).toHashSet())
                        previousResource.forEach { r->
                            dependencies[r]!!.remove(mu)
                        }
                        newTargetSet.forEach { r ->
                            dependencies.getOrPut(r){ mutableListOf()}.add(newMut)
                        }

                    }
                }else{
                    val newMut = MutualResourcesRelations(relatedResource.toMutableList(), 1.0, mutableSetOf(table))
                    relatedResource.forEach { r ->
                        dependencies.getOrPut(r){ mutableListOf()}.add(newMut)
                    }
                }
            }
        }
    }

    private fun updateResourceToTable(action: RestCallAction, updated: Map<String, MutableSet<String>>, matchedWithVerb : Boolean,
                addedMap: MutableMap<String, MutableSet<String>>, removedMap: MutableMap<String, MutableSet<String>>){
        val ar = rm.getRestResource(action.path.toString())!!
        val rToTable = ar.resourceToTable

        if(updated.isNotEmpty() && matchedWithVerb){

            if(action.verb != HttpVerb.GET){
                val derivedTables = rToTable.getTablesInDerivedMap()

                updated.forEach { t, u ->
                    if(derivedTables.contains(t)){
                        if(action.parameters.isNotEmpty() && u.isNotEmpty() && u.none { it == "*" }){
                            action.parameters.forEach { p->
                                val paramId = ar.getParamId(action.parameters, p)
                                ar.resourceToTable.paramToTable[paramId]?.let { paramToTable->
                                    paramToTable.getRelatedColumn(t)?.apply {
                                        paramToTable.confirmedColumn.addAll(this.intersect(u))
                                    }
                                }
                            }
                        }
                    }else{
                        val matchedInfo = ResourceRelatedToTable.generateFromDtoMatchedInfo(DbUtil.formatTableName(t))
                        ar.resourceToTable.derivedMap.put(t, mutableListOf(matchedInfo))
                        action.parameters.forEach { p->
                            val paramId = ar.getParamId(action.parameters, p)
                            val paramInfo = ar.paramsInfo[paramId].run {
                                if(this == null) ar.updateAdditionalParam(action, p).also {
                                    deriveParamsToTable(paramId, it, ar)
                                } else this
                            }
                                   // ?:throw IllegalArgumentException("cannot find the param Id $paramId in the rest resource ${ar.getName()}")
                            deriveRelatedTable(ar, paramId, paramInfo, mutableSetOf(t) , false, -1)

                            ar.resourceToTable.paramToTable[paramId]?.let { paramToTable->
                                paramToTable.getRelatedColumn(t)?.apply {
                                    paramToTable.confirmedColumn.addAll(this.intersect(u))
                                }
                            }
                        }

                        addedMap.getOrPut(ar.getName()){ mutableSetOf()}.add(t)

                    }

                    rToTable.confirmedSet.getOrPut(t){true}
                    rToTable.confirmedSet[t] = true
                }
            }else{
                val derivedTables = rToTable.getTablesInDerivedMap()
                updated.forEach { t, u ->
                    if(derivedTables.contains(t)){
                        rToTable.confirmedSet.getOrPut(t){true}
                        rToTable.confirmedSet[t] = true
                    }else{
                        rToTable.confirmedSet.getOrPut(t){false}
                    }
                }
            }
        }else{
            updated.keys.forEach { t ->
                rToTable.confirmedSet.getOrPut(t){false}
            }
        }
    }

    private fun updateResourceToTable(action: RestCallAction, dto: ExecutionDto,
                              addedMap: MutableMap<String, MutableSet<String>>, removedMap: MutableMap<String, MutableSet<String>>){

        dto.insertedData.filter { u -> dataInDB.any { it.key.toLowerCase() == u.key } }.let {added ->
            updateResourceToTable(action, added, (action.verb == HttpVerb.POST || action.verb == HttpVerb.PUT),addedMap, removedMap)
        }

        dto.updatedData.filter { u -> dataInDB.any { it.key.toLowerCase() == u.key } }.let {updated->
            updateResourceToTable(action, updated, (action.verb == HttpVerb.PATCH || action.verb == HttpVerb.PUT),addedMap, removedMap)
        }

        dto.deletedData.filter { u -> dataInDB.any { it.key.toLowerCase() == u } }.let {del->
            updateResourceToTable(action, del.map { Pair(it, mutableSetOf<String>()) }.toMap(), (action.verb == HttpVerb.PATCH || action.verb == HttpVerb.PUT),addedMap, removedMap)

        }
        dto.queriedData.filter { u -> dataInDB.any { it.key.toLowerCase() == u.key } }.let {get->
            updateResourceToTable(action, get, (action.verb == HttpVerb.PATCH || action.verb == HttpVerb.PUT),addedMap, removedMap)
        }

        rm.getRestResource(action.path.toString())!!.resourceToTable.updateActionRelatedToTable(action.verb.toString(), dto, dataInDB.keys)
    }


    /**
     * derive relationship between resource and tables,
     *          i.e., the action under the resource may manipulate data from the table
     * e.g., /A/{a}/B{b}, the resource is related to two resource A and B
     */
    fun deriveResourceToTable(resourceCluster: MutableList<RestResource>){

        resourceCluster.forEach { r->
            //1. derive resource to table
            //1.1 derive resource to tables based on segments
            r.getSegments().filter { it != RestResource.ALL_SYMBOL }.forEach { seg ->
                ParamUtil.parseParams(seg).reversed().forEachIndexed stop@{ sindex, token ->
                    //check whether any table name matches token
                    val matchedMap = tables.keys.map { Pair(it, ParserUtil.stringSimilarityScore(it, token)) }.asSequence().sortedBy { e->e.second }
                    if(matchedMap.last().second >= ParserUtil.SimilarityThreshold){
                        matchedMap.filter { it.second == matchedMap.last().second }.forEach {
                            r.resourceToTable.derivedMap.getOrPut(it.first){
                                mutableListOf()
                            }.add(MatchedInfo(seg, it.first, similarity = it.second, inputIndicator = sindex, outputIndicator = 0))
                        }
                        return@stop
                    }
                }
            }
            //1.2 derive resource to tables based on type
            val reftypes = r.getMethods().filter { (it is RestCallAction) && it.parameters.any{p-> p is BodyParam && p.gene is ObjectGene && p.gene.refType != null}}
                    .flatMap { (it as RestCallAction ).parameters.filter{p-> p is BodyParam && p.gene is ObjectGene && p.gene.refType != null}.map { p-> (p.gene as ObjectGene).refType!!}}

            if(reftypes.isNotEmpty()){
                reftypes.forEach { type->
                    if(!r.isPartOfStaticTokens(type)){
                        val matchedMap = tables.keys.map { Pair(it, ParserUtil.stringSimilarityScore(it, type)) }.asSequence().sortedBy { e->e.second }
                        if(matchedMap.last().second >= ParserUtil.SimilarityThreshold){
                            matchedMap.filter { it.second == matchedMap.last().second }.forEach {
                                r.resourceToTable.derivedMap.getOrPut(it.first){
                                    mutableListOf()
                                }.add(MatchedInfo(type, it.first, similarity = it.second, inputIndicator = 0, outputIndicator = 0))
                            }
                        }
                    }
                }
            }
            //2. derive params to the tables
            deriveParamsToTable(r.paramsInfo, r)
        }
    }

    private fun deriveParamsToTable(mapParamInfo : Map<String, RestResource.ParamInfo>, r: RestResource){
        mapParamInfo.forEach { paramId, paramInfo ->
            deriveParamsToTable(paramId, paramInfo, r)
        }
    }

    private fun deriveParamsToTable(paramId : String, paramInfo : RestResource.ParamInfo, r: RestResource){

        val inputIndicator = r.getSegmentLevel(paramInfo.previousSegment)
        val relatedTables = r.resourceToTable.derivedMap.filter { it.value.any { m-> m.input == paramInfo.previousSegment } }.keys.toHashSet()

        val isBodyParam = (paramInfo.referParam is BodyParam) && (paramInfo.referParam.gene is ObjectGene && paramInfo.referParam.gene.fields.isNotEmpty())

        var created = false
        if(isBodyParam){
            var tables = if((paramInfo.referParam.gene as ObjectGene).refType != null){
                r.resourceToTable.derivedMap.filter { it.value.any { m-> m.input == (paramInfo.referParam.gene as ObjectGene).refType} }.keys.toHashSet()
            } else null

            if(tables == null || tables.isEmpty()) tables = relatedTables
            created = deriveRelatedTable(r, paramId, paramInfo, tables, isBodyParam, inputIndicator )
        }

        if(!created){
            created = deriveRelatedTable(r, paramId, paramInfo, relatedTables, false, inputIndicator)
        }


    }

    private fun deriveRelatedTable(r : RestResource, paramId: String, paramInfo: RestResource.ParamInfo, relatedToTables: Set<String>, isBodyParam : Boolean, inputIndicator: Int) : Boolean{
        if(isBodyParam){
            assert(paramInfo.referParam.gene is ObjectGene && paramInfo.referParam.gene.fields.isNotEmpty())
            var pToTable = BodyParamRelatedToTable(paramId, paramInfo.referParam)

            (paramInfo.referParam.gene as ObjectGene).fields.forEach { f->
                val matchedMap : MutableMap<String, MatchedInfo> = mutableMapOf()
                deriveParamWithTable(f.name, relatedToTables, matchedMap, inputIndicator)
                if(matchedMap.isNotEmpty()){
                    val fToTable = ParamFieldRelatedToTable(f.name)
                    fToTable.derivedMap.putAll(matchedMap)
                    pToTable.fieldsMap.putIfAbsent(f.name, fToTable)
                }
            }
            if(pToTable.fieldsMap.isNotEmpty()) {
                r.resourceToTable.paramToTable.putIfAbsent(paramId, pToTable)
                return true
            }
        }else{
            val matchedMap : MutableMap<String, MatchedInfo> = mutableMapOf()
            deriveParamWithTable(paramInfo.name, relatedToTables, matchedMap, inputIndicator)
            if(matchedMap.isNotEmpty()){
                val pToTable = SimpleParamRelatedToTable(paramId, paramInfo.referParam)
                pToTable.derivedMap.putAll(matchedMap)
                r.resourceToTable.paramToTable.putIfAbsent(paramId, pToTable)
                return true
            }
        }
        return false
    }


    private fun deriveParamWithTable(paramName : String, tables : Set<String>, pToTable : MutableMap<String, MatchedInfo>, inputlevel: Int){
        tables.forEach { tableName ->
            deriveParamWithTable(paramName, tableName, pToTable, inputlevel)
        }
    }

    private fun deriveParamWithTable(paramName : String, tableName: String, pToTable : MutableMap<String, MatchedInfo>, inputlevel: Int){
        /*
            paramName might be \w+id or \w+name, in this case, we compare paramName with table name + column name
         */
        val suffixOfTable = if (ParamUtil.containGeneralName(paramName)) tableName else ""
        getTable(tableName)?.let { t->
            val matchedMap = t.columns.map { Pair(it.name, ParserUtil.stringSimilarityScore(paramName, "$suffixOfTable${it.name}")) }.asSequence().sortedBy { e->e.second }
            if(matchedMap.last().second >= ParserUtil.SimilarityThreshold){
                matchedMap.filter { it.second == matchedMap.last().second }.forEach {
                    pToTable.getOrPut(tableName){
                        MatchedInfo(paramName, it.first, similarity = it.second, inputIndicator = inputlevel, outputIndicator = 1)
                    }
                }
            }
        }
    }


    private fun getTable(tableName: String) : Table?{
        return tables.values.find{ it.name.toLowerCase() == tableName.toLowerCase()}
    }

    /************************  derive dependency using parser ***********************************/

    private fun initDependencyBasedOnDerivedTables(resourceCluster: MutableList<RestResource>){
        dataInDB.keys.forEach { table->
            val mutualResources = resourceCluster.filter { r -> r.getDerivedTables().any { e -> DbUtil.formatTableName(e) == DbUtil.formatTableName(table)}}.map { it.getName() }.toList()
            if(mutualResources.isNotEmpty() && mutualResources.size > 1){
                val mutualRelation = MutualResourcesRelations(mutualResources, ParserUtil.SimilarityThreshold, mutableSetOf(DbUtil.formatTableName(table)))

                mutualResources.forEach { res ->
                    val relations = dependencies.getOrPut(res){ mutableListOf()}
                    relations.find { r-> r is MutualResourcesRelations && r.targets.contains(mutualRelation.targets)}.let {
                        if(it == null)
                            relations.add(mutualRelation)
                        else
                            (it as MutualResourcesRelations).referredTables.add(DbUtil.formatTableName(table))
                    }
                }
            }
        }
    }

    /**
     * to derive dependency based on schema, i.e., description of each action if exists.
     *
     * If a description of a Post action includes some tokens (the token must be some "object") that is related to other rest action,
     * we create a "possible dependency" between the actions.
     */
    private fun deriveDependencyBasedOnSchema(resourceCluster: MutableList<RestResource>){
        resourceCluster
                .filter { it.getMethods().filter { it is RestCallAction && it.verb == HttpVerb.POST }.isNotEmpty() }
                .forEach { r->
                    /*
                     TODO Man should only apply on POST Action? how about others?
                     */
                    val post = r.getMethods().first { it is RestCallAction && it.verb == HttpVerb.POST }!! as RestCallAction
                    post.tokens.forEach { _, u ->
                        resourceCluster.forEach { or ->
                            if(or != r){
                                or.getMethods()
                                        .filter { it is RestCallAction }
                                        .flatMap { (it as RestCallAction).tokens.values.filter { t -> t.fromDefinition && t.isDirect && t.isType } }
                                        .filter{ot ->
                                            ParserUtil.stringSimilarityScore(u.getKey(), ot.getKey()) >= ParserUtil.SimilarityThreshold
                                        }.let {
                                            if(it.isNotEmpty()){
                                                val addInfo = it.map { t-> t.getKey()}.joinToString(";")
                                                updateDependencies(or.getName(), mutableListOf(r.getName()), addInfo, ParserUtil.SimilarityThreshold)
                                                updateDependencies(r.getName(), mutableListOf(or.getName()), addInfo, ParserUtil.SimilarityThreshold)
                                            }

                                        }

                            }
                        }
                    }
                }
    }


    /************************  utility ***********************************/

    /**
     * update dependencies based on derived info
     * [additionalInfo] is structure mutator in this context
     */
    private fun updateDependencies(key : String, target : MutableList<String>, additionalInfo : String, probability : Double = 1.0){

        val relation = if(target.size == 1 && target[0] == key) SelfResourcesRelation(key, probability, additionalInfo)
        else ResourceRelatedToResources(listOf(key), target, probability, info = additionalInfo)

        updateDependencies(relation, additionalInfo)
    }

    private fun updateDependencies(relation : ResourceRelatedToResources, additionalInfo: String){
        val found = dependencies.getOrPut(relation.originalKey()){ mutableListOf()}.find { it.targets.containsAll(relation.targets) }
        if (found == null) dependencies[relation.originalKey()]!!.add(relation)
        else {
            /*
                TODO Man a strategy to manipulate the probability
             */
            found.probability = max(found.probability,relation.probability)
            if(found.additionalInfo.isBlank())
                found.additionalInfo = additionalInfo
            else if(!found.additionalInfo.contains(additionalInfo))
                found.additionalInfo += ";$additionalInfo"
        }
    }



    fun findDependentResources(ind: ResourceRestIndividual, call : ResourceRestCalls, minProbability : Double = 0.0, maxProbability : Double = 1.0): MutableList<ResourceRestCalls>{
        return ind.getResourceCalls().filter {other ->
            (other != call) && dependencies[call.resourceInstance.getAResourceKey()]?.find { r->r.targets.contains(other.resourceInstance.getAResourceKey()) && r.probability >= minProbability&& r.probability <= maxProbability} !=null
        }.toMutableList()
    }

    fun findNonDependentResources(ind: ResourceRestIndividual, call : ResourceRestCalls): MutableList<ResourceRestCalls>{
        return ind.getResourceCalls().filter { other ->
            (other != call) && nondependencies[call.resourceInstance.getAResourceKey()]?.contains(other.resourceInstance.getAResourceKey())?:false
        }.toMutableList()
    }

    fun existsDependentResources(ind: ResourceRestIndividual, call : ResourceRestCalls, minProbability : Double = 0.0, maxProbability : Double = 1.0): Boolean{
        return ind.getResourceCalls().find {other ->
            (other != call) && dependencies[call.resourceInstance.getAResourceKey()]?.find { r->r.targets.contains(other.resourceInstance.getAResourceKey()) && r.probability >= minProbability && r.probability <= maxProbability} !=null
        }!=null
    }

    fun isNonDepResources(ind: ResourceRestIndividual, call : ResourceRestCalls): Boolean{
        return ind.getResourceCalls().find {other ->
            (other != call) && nondependencies[other.resourceInstance.getAResourceKey()]?.contains(call.resourceInstance.getAResourceKey())?:false
        }!=null
    }

    /************************  detect dependency based on fitnesss ***********************************/

    private fun detectAfterSwap(previous : EvaluatedIndividual<ResourceRestIndividual>, current : EvaluatedIndividual<ResourceRestIndividual>, isBetter: Int){
        val seqPre = previous.individual.getResourceCalls()
        val seqCur = current.individual.getResourceCalls()

        /*
        For instance, ABCDEFG, if we swap B and F, become AFCDEBG, then check FCDE (do not include B!).
        if F is worse, F may rely on {C, D, E, B}
        if C is worse, C rely on B; else if C is better, C rely on F; else C may not rely on B and F

        there is another case regarding duplicated resources calls (i.e., same resource and same actions) in a test,
        for instance, ABCDB*B**EF, swap B and F, become AFCDB*B**EB, in this case,
        B* probability become better, B** is same, B probability become worse
        */

        //find the element is not in the same position
        val swapsloc = mutableListOf<Int>()

        seqCur.forEachIndexed { index, restResourceCalls ->
            if(restResourceCalls.resourceInstance.getKey() != seqPre[index].resourceInstance.getKey())
                swapsloc.add(index)
        }

        assert(swapsloc.size == 2)
        val swapF = seqCur[swapsloc[0]]
        val swapB = seqCur[swapsloc[1]]

        if(isBetter != 0){
            val locOfF = swapsloc[0]
            val distance = swapF.actions.size - swapB.actions.size

            //check F
            val middles = seqCur.subList(swapsloc[0]+1, swapsloc[1]+1).map { it.resourceInstance.getAResourceKey() }
            if(ComparisionUtil.compare(swapsloc[0], current, swapsloc[1], previous) != 0){
                middles.forEach {
                    updateDependencies(swapF.resourceInstance.getAResourceKey(), mutableListOf(it),ResourceRestStructureMutator.MutationType.SWAP.toString(), (1.0/middles.size))
                }
            }else{
                nondependencies.getOrPut(swapF.resourceInstance.getAResourceKey()){ mutableSetOf()}.apply {
                    addAll(middles.toHashSet())
                }
            }

            //check FCDE
            var actionIndex = seqCur.mapIndexed { index, restResourceCalls ->
                if(index <= locOfF) restResourceCalls.actions.size
                else 0
            }.sum()

            ( (locOfF + 1) until swapsloc[1] ).forEach { indexOfCalls ->
                var isAnyChange = false
                var changeDegree = 0

                seqCur[indexOfCalls].actions.forEach {curAction->
                    val actionA = actionIndex - distance

                    val compareResult = swapF.actions.plus(swapB.actions).find { it.getName() == curAction.getName() }.run {
                        if(this == null) ComparisionUtil.compare(actionIndex, current, actionA, previous)
                        else ComparisionUtil.compare(this.getName(), current, previous)
                    }.also { r-> changeDegree += r }

                    isAnyChange = isAnyChange || compareResult!=0
                    actionIndex += 1
                    //isAnyChange = isAnyChange || compare(actionA, current, actionIndex, previous).also { r-> changeDegree += r } !=0
                }

                val seqKey = seqCur[indexOfCalls].resourceInstance.getAResourceKey()
                if(isAnyChange){

                    val relyOn = if(changeDegree > 0){
                        mutableListOf(swapF!!.resourceInstance.getAResourceKey())
                    }else if(changeDegree < 0){
                        mutableListOf(swapB!!.resourceInstance.getAResourceKey())
                    }else
                        mutableListOf(swapB!!.resourceInstance.getAResourceKey(), swapF!!.resourceInstance.getAResourceKey())

                    updateDependencies(seqKey, relyOn, ResourceRestStructureMutator.MutationType.SWAP.toString())
                }else{
                    nondependencies.getOrPut(seqKey){ mutableSetOf()}.apply {
                        add(swapB.resourceInstance.getAResourceKey())
                        add(swapF.resourceInstance.getAResourceKey())
                    }
                }
            }

            val before = seqCur.subList(swapsloc[0], swapsloc[1]).map { it.resourceInstance.getAResourceKey() }
            if(ComparisionUtil.compare(swapsloc[1], current, swapsloc[0], previous) != 0){
                middles.forEach {
                    updateDependencies(swapB.resourceInstance.getAResourceKey(), mutableListOf(it),ResourceRestStructureMutator.MutationType.SWAP.toString(), (1.0/before.size))
                }
            }else{
                nondependencies.getOrPut(swapB.resourceInstance.getAResourceKey()){ mutableSetOf()}.addAll(before)
            }

            //TODO check G, a bit complicated,

        }else{
            /*
                For instance, ABCDEFG, if we swap B and F, become AFCDEBG.
                if there is no any impact on fitness,
                    1) it probably means {C,D,E} does not rely on B and F
                    2) F does not rely on {C, D, E}
                    3) F does not rely on B
             */
            val middles = seqCur.subList(swapsloc[0]+1, swapsloc[1]+1).map { it.resourceInstance.getAResourceKey() }
            middles.forEach { c->
                nondependencies.getOrPut(c){ mutableSetOf()}.apply {
                    add(swapB.resourceInstance.getAResourceKey())
                    add(swapF.resourceInstance.getAResourceKey())
                }
                nondependencies.getOrPut(swapF.resourceInstance.getAResourceKey()){ mutableSetOf()}.add(c)
            }
            nondependencies.getOrPut(swapF.resourceInstance.getAResourceKey()){ mutableSetOf()}.add(swapB.resourceInstance.getAResourceKey())
        }
    }

    private fun detectAfterModify(previous : EvaluatedIndividual<ResourceRestIndividual>, current : EvaluatedIndividual<ResourceRestIndividual>, isBetter: Int){
        val seqPre = previous.individual.getResourceCalls()
        val seqCur = current.individual.getResourceCalls()

        //MODIFY
        /*
            For instance, ABCDEFG, if we replace B with another resource instance, then check CDEFG.
            if C is worse/better, C rely on B, else C may not rely on B, i.e., the changes of B cannot affect C.
         */
        if(isBetter != 0){
            val locOfModified = (0 until seqCur.size).find { seqPre[it].template.template != seqCur[it].template.template }?:
            return
            //throw IllegalArgumentException("mutator does not change anything.")

            val modified = seqCur[locOfModified]
            val distance = seqCur[locOfModified].actions.size - seqPre[locOfModified].actions.size

            var actionIndex = seqCur.mapIndexed { index, restResourceCalls ->
                if(index <= locOfModified) restResourceCalls.actions.size
                else 0
            }.sum()

            ((locOfModified + 1) until seqCur.size).forEach { indexOfCalls ->
                var isAnyChange = false
                seqCur[indexOfCalls].actions.forEach {curAction ->
                    val actionA = actionIndex - distance
                    isAnyChange = isAnyChange || ComparisionUtil.compare(actionIndex, current, actionA, previous) !=0
                    actionIndex += 1
                }

                if(isAnyChange){
                    val seqKey = seqCur[indexOfCalls].resourceInstance.getAResourceKey()
                    updateDependencies(seqKey, mutableListOf(modified!!.resourceInstance.getAResourceKey()), ResourceRestStructureMutator.MutationType.MODIFY.toString())
                }
            }
        }
    }

    private fun detectAfterReplace(previous : EvaluatedIndividual<ResourceRestIndividual>, current : EvaluatedIndividual<ResourceRestIndividual>, isBetter: Int){
        val seqPre = previous.individual.getResourceCalls()
        val seqCur = current.individual.getResourceCalls()

        /*
                        For instance, ABCDEFG, if we replace B with H become AHCDEFG, then check CDEFG.
                        if C is worse, C rely on B; else if C is better, C rely on H; else C may not rely on B and H

                     */

        val mutatedIndex = (0 until seqCur.size).find { seqCur[it].resourceInstance.getKey() != seqPre[it].resourceInstance.getKey() }!!

        val replaced = seqCur[mutatedIndex]!!
        val replace = seqPre[mutatedIndex]!!

        if(isBetter != 0){
            val locOfReplaced = seqCur.indexOf(replaced)
            val distance = locOfReplaced - seqPre.indexOf(replace)

            var actionIndex = seqCur.mapIndexed { index, restResourceCalls ->
                if(index <= locOfReplaced) restResourceCalls.actions.size
                else 0
            }.sum()

            ( (locOfReplaced + 1) until seqCur.size ).forEach { indexOfCalls ->
                var isAnyChange = false
                var changeDegree = 0
                seqCur[indexOfCalls].actions.forEach {curAction->
                    val actionA = actionIndex - distance

                    val compareResult = replaced.actions.plus(replace.actions).find { it.getName() == curAction.getName() }.run {
                        if(this == null) ComparisionUtil.compare(actionIndex, current, actionA, previous)
                        else ComparisionUtil.compare(this.getName(), current, previous)
                    }.also { r-> changeDegree += r }

                    isAnyChange = isAnyChange || compareResult!=0
                    actionIndex += 1

                    //isAnyChange = isAnyChange || compare(actionA, current, actionIndex, previous).also { r-> changeDegree += r } !=0
                }

                val seqKey = seqCur[indexOfCalls].resourceInstance.getAResourceKey()
                if(isAnyChange){

                    val relyOn = if(changeDegree > 0){
                        mutableListOf(replaced.resourceInstance.getAResourceKey())
                    }else if(changeDegree < 0){
                        mutableListOf(replace.resourceInstance.getAResourceKey())
                    }else
                        mutableListOf(replaced.resourceInstance.getAResourceKey(), replace.resourceInstance.getAResourceKey())

                    updateDependencies(seqKey, relyOn, ResourceRestStructureMutator.MutationType.REPLACE.toString())
                }else{
                    nondependencies.getOrPut(seqKey){ mutableSetOf()}.apply {
                        add(replaced.resourceInstance.getAResourceKey())
                        add(replace.resourceInstance.getAResourceKey())
                    }
                }
            }

        }else{
            /*
            For instance, ABCDEFG, if we replace B with H become AHCDEFG, then check CDEFG.
            if there is no any impact on fitness, it probably means {C, D, E, F, G} does not rely on B and H
            */
            ((mutatedIndex + 1) until seqCur.size).forEach {
                val non = seqCur[it].resourceInstance.getAResourceKey()
                nondependencies.getOrPut(non){ mutableSetOf()}.apply {
                    add(replaced.resourceInstance.getAResourceKey())
                    add(replace.resourceInstance.getAResourceKey())
                }
            }
        }
    }

    private fun detectAfterAdd(previous : EvaluatedIndividual<ResourceRestIndividual>, current : EvaluatedIndividual<ResourceRestIndividual>, isBetter: Int){
        val seqPre = previous.individual.getResourceCalls()
        val seqCur = current.individual.getResourceCalls()

        /*
                     For instance, ABCDEFG, if we add H at 3nd position, become ABHCDEFG, then check CDEFG.
                     if C is better, C rely on H; else if C is worse, C rely on H ? ;else C may not rely on H
                */
        val added = seqCur.find { cur -> seqPre.find { pre-> pre.resourceInstance.getKey() == cur.resourceInstance.getKey() } == null }?: return
        val addedKey = added!!.resourceInstance.getAResourceKey()

        val locOfAdded = seqCur.indexOf(added!!)

        if(isBetter != 0){
            var actionIndex = seqCur.mapIndexed { index, restResourceCalls ->
                if(index <= locOfAdded) restResourceCalls.actions.size
                else 0
            }.sum()

            val distance = added!!.actions.size

            (locOfAdded+1 until seqCur.size).forEach { indexOfCalls ->
                var isAnyChange = false

                seqCur[indexOfCalls].actions.forEach { curAction->
                    var actionA = actionIndex - distance
                    val compareResult = added.actions.find { it.getName() == curAction.getName() }.run {
                        if(this == null) ComparisionUtil.compare(actionIndex, current, actionA, previous)
                        else ComparisionUtil.compare(this.getName(), current, previous)
                    }

                    isAnyChange = isAnyChange || compareResult!=0
                    actionIndex += 1 //actionB
                }
                val seqKey = seqCur[indexOfCalls].resourceInstance.getAResourceKey()
                if(isAnyChange){
                    updateDependencies(seqKey, mutableListOf(addedKey), ResourceRestStructureMutator.MutationType.ADD.toString())
                }else{
                    nondependencies.getOrPut(seqKey){ mutableSetOf()}.add(addedKey)
                }
            }

        }else{
            /*
            For instance, ABCDEFG, if we add H at 3nd position, become ABHCDEFG.
            if there is no any impact on fitness, it probably means {C, D, E, F, G} does not rely on H
             */
            (locOfAdded + 1 until seqCur.size).forEach {
                val non = seqCur[it].resourceInstance.getAResourceKey()
                nondependencies.getOrPut(non){ mutableSetOf()}.add(addedKey)
            }
        }
    }

    private fun detectAfterDelete(previous : EvaluatedIndividual<ResourceRestIndividual>, current : EvaluatedIndividual<ResourceRestIndividual>, isBetter: Int){
        val seqPre = previous.individual.getResourceCalls()
        val seqCur = current.individual.getResourceCalls()

        /*
                     For instance, ABCDEFG, if B is deleted, become ACDEFG, then check CDEFG.
                     if C is worse, C rely on B;
                        else if C is better, C rely one B ?;
                        else C may not rely on B.

                     there is another case regarding duplicated resources calls (i.e., same resource and same actions) in a test, for instance, ABCB* (B* denotes the 2nd B), if B is deleted, become ACB*, then check CB* as before,
                     when comparing B*, B* probability achieves better performance by taking target from previous first B, so we need to compare with merged targets, i.e., B and B*.
                */
        val delete = seqPre.find { pre -> seqCur.find { cur-> pre.resourceInstance.getKey() == cur.resourceInstance.getKey() } == null }?:return
        val deleteKey = delete!!.resourceInstance.getAResourceKey()

        val locOfDelete = seqPre.indexOf(delete!!)

        if(isBetter != 0){

            var actionIndex = seqPre.mapIndexed { index, restResourceCalls ->
                if(index < locOfDelete) restResourceCalls.actions.size
                else 0
            }.sum()

            val distance = 0 - delete!!.actions.size

            (locOfDelete until seqCur.size).forEach { indexOfCalls ->
                var isAnyChange = false

                seqCur[indexOfCalls].actions.forEach { curAction ->
                    val actionA = actionIndex - distance

                    val compareResult = delete.actions.find { it.getName() == curAction.getName() }.run {
                        if(this == null) ComparisionUtil.compare(actionIndex, current, actionA, previous)
                        else ComparisionUtil.compare(this.getName(), current, previous)
                    }

                    isAnyChange = isAnyChange || compareResult!=0
                    actionIndex += 1 //actionB
                }

                val seqKey = seqCur[indexOfCalls].resourceInstance.getAResourceKey()
                if(isAnyChange){
                    updateDependencies(seqKey, mutableListOf(deleteKey), ResourceRestStructureMutator.MutationType.DELETE.toString())
                }else{
                    nondependencies.getOrPut(seqKey){ mutableSetOf()}.add(deleteKey)
                }
            }
        }else{
            /*
              For instance, ABCDEFG, if B is deleted, become ACDEFG, then check CDEFG.
              if there is no impact on fitness, it probably means {C, D, E, F, G} does not rely on B
             */
            (locOfDelete until seqCur.size).forEach {
                val non = seqCur[it].resourceInstance.getAResourceKey()
                nondependencies.getOrPut(non){ mutableSetOf()}.add(deleteKey)
            }

        }
    }

    /**
     * detect possible dependency among resources,
     * the entry is structure mutation
     *
     * [isBetter] 1 means current is better than previous, 0 means that they are equal, and -1 means current is worse than previous
     */
    fun detectDependencyAfterStructureMutation(previous : EvaluatedIndividual<ResourceRestIndividual>, current : EvaluatedIndividual<ResourceRestIndividual>, isBetter: Int){
        val seqPre = previous.individual.getResourceCalls()
        val seqCur = current.individual.getResourceCalls()

        when(seqCur.size - seqPre.size){
            0 ->{
                if(seqPre.map { it.resourceInstance.getAResourceKey() }.toList() == seqCur.map { it.resourceInstance.getAResourceKey() }.toList()){
                    //Modify
                    detectAfterModify(previous, current, isBetter)
                }else if(seqCur.size > 1 && seqPre.map { it.resourceInstance.getAResourceKey() }.toSet() == seqCur.map { it.resourceInstance.getAResourceKey() }.toSet()){
                    //SWAP
                    detectAfterSwap(previous, current, isBetter)
                }else{
                    //REPLACE
                    detectAfterReplace(previous, current, isBetter)
                }
            }
            1 -> detectAfterAdd(previous, current, isBetter)
            -1 -> detectAfterDelete(previous, current, isBetter)
            else ->{
                throw IllegalArgumentException("apply undefined structure mutator that changed the size of resources from ${seqPre.size} to ${seqCur.size}")
            }
        }

    }

    /************************  database ***********************************/

    fun getRowInDataInDB(tableName: String) : MutableList<DataRowDto>?{
        dataInDB[tableName]?.let { return it}
        dataInDB[tableName.toLowerCase()]?.let { return it }
        dataInDB[tableName.toUpperCase()]?.let { return it }
        return null
    }

    /**
     * update existing data in db
     * the existing data can be applied to an sampled individual
     */
    fun snapshotDB(sql : SqlInsertBuilder){
        sql.extractExistingPKs(dataInDB)
    }


}

