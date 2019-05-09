package org.evomaster.core.problem.rest.resource.service

import com.google.inject.Inject
import org.apache.commons.lang3.mutable.Mutable
import org.evomaster.client.java.controller.api.dto.TestResultsDto
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
import org.evomaster.core.problem.rest.param.PathParam
import org.evomaster.core.problem.rest.param.QueryParam
import org.evomaster.core.problem.rest.resource.ResourceRestIndividual
import org.evomaster.core.problem.rest.resource.model.RestResource
import org.evomaster.core.problem.rest.resource.model.ResourceRestCalls
import org.evomaster.core.problem.rest.resource.model.dependency.MutualResourcesRelations
import org.evomaster.core.problem.rest.resource.model.dependency.ParamRelatedToTable
import org.evomaster.core.problem.rest.resource.model.dependency.ResourceRelatedToResources
import org.evomaster.core.problem.rest.resource.model.dependency.SelfResourcesRelation
import org.evomaster.core.problem.rest.resource.util.ComparisionUtil
import org.evomaster.core.problem.rest.resource.util.ParamUtil
import org.evomaster.core.problem.rest.resource.util.ParserUtil
import org.evomaster.core.problem.rest.resource.util.RTemplateHandler
import org.evomaster.core.search.Action
import org.evomaster.core.search.EvaluatedIndividual
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

    /**
     * key is resource path
     * value is an abstract resource
     */
    private val resourceCluster : MutableMap<String, RestResource> = mutableMapOf()

    /**
     * key is resource path
     * value is a list of tables that are related to the resource
     */
    private val resourceTables : MutableMap<String, MutableSet<String>> = mutableMapOf()

    /**
     * key is table name
     * value is a list of existing data of PKs in DB
     */
    private val dataInDB : MutableMap<String, MutableList<DataRowDto>> = mutableMapOf()

    /**
     * key is either a path of one resource, or a list of paths of resources
     * value is a list of related to resources
     */
    private val dependencies : MutableMap<String, MutableList<ResourceRelatedToResources>> = mutableMapOf()

    private var flagInitDep = false

    fun initAbstractResources(actionCluster : MutableMap<String, Action>) {
        actionCluster.values.forEach { u ->
            if (u is RestCallAction) {
                val resource = resourceCluster.getOrPut(u.path.toString()) {
                    RestResource(u.path.copy(), mutableListOf()).also {
                        if (config.doesApplyTokenParser)
                            it.initTokens()
                    }
                }
                resource.actions.add(u)
            }
        }
        resourceCluster.values.forEach{it.initAncestors(getResourceCluster().values.toList())}

        resourceCluster.values.forEach{it.init()}

        if(hasDBHandler()){
            snapshotDB()
            /*
                derive possible db creation for each abstract resources.
                The derived db creation needs to be further confirmed based on feedback from evomaster driver (NOT IMPLEMENTED YET)
             */
            resourceCluster.values.forEach {ar->
                if(ar.paramsToTables.isEmpty() && config.doesApplyTokenParser)
                    deriveRelatedTables(ar,false)
            }
        }

        if(config.doesApplyTokenParser)
            initDependency()

    }

    /**
     * [resourceTables] and [RestResource.paramsToTables] are basic ingredients for an initialization of [dependencies]
     * thus, the starting point to invoke [initDependency] depends on when the ingredients are ready.
     *
     * if [EMConfig.doesApplyTokenParser] the invocation happens when init resource cluster,
     * else the invocation happens when all ad-hoc individuals are executed
     *
     * Note that it can only be executed one time
     */
    fun initDependency(){
        if(config.probOfEnablingResourceDependencyHeuristics == 0.0 || flagInitDep) return

        flagInitDep = true

        //1. based on resourceTables to identify mutual relations among resources
        initDependencyBasedOnResourceTables()

        //2. for each resource, identify relations based on derived table
        if(config.doesApplyTokenParser)
            initDependencyBasedOnParamRelatedTables()

    }

    private fun initDependencyBasedOnResourceTables(){
        resourceTables.values.flatten().toSet().forEach { tab->
            val mutualResources = resourceTables.filter { it.value.contains(tab) }.map { it.key }.toHashSet().toList()

            if(mutualResources.isNotEmpty() && mutualResources.size > 1){
                val mutualRelation = MutualResourcesRelations(mutualResources, 1.0, mutableSetOf(tab))

                mutualResources.forEach { res ->
                    val relations = dependencies.getOrPut(res){ mutableListOf()}
                    if(relations.find { r-> r.targets.contains(mutualRelation.targets) && r.additionalInfo == mutualRelation.additionalInfo } == null )
                        relations.add(mutualRelation)
                }
            }
        }
    }


    /**
     * detect possible dependency among resources,
     * the entry is structure mutation
     *
     * [isBetter] 1 means current is better than previous, 0 means that they are equal, and -1 means current is worse than previous
     */
    fun detectDependency(previous : EvaluatedIndividual<ResourceRestIndividual>, current : EvaluatedIndividual<ResourceRestIndividual>, isBetter: Int){
        val seqPre = previous.individual.getResourceCalls()
        val seqCur = current.individual.getResourceCalls()

        when(seqCur.size - seqPre.size){
            0 ->{
                //SWAP, MODIFY, REPLACE are on the category
                if(seqPre.map { it.resourceInstance.getAResourceKey() }.toList() == seqCur.map { it.resourceInstance.getAResourceKey() }.toList()){
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


                }else if(seqPre.map { it.resourceInstance.getAResourceKey() }.toSet() == seqCur.map { it.resourceInstance.getAResourceKey() }.toSet()){
                    //SWAP
                    /*
                        For instance, ABCDEFG, if we swap B and F, become AFCDEBG, then check FCDE (do not include B!).
                        if F is worse, F may rely on {C, D, E, B}
                        if C is worse, C rely on B; else if C is better, C rely on F; else C may not rely on B and F

                        there is another case regarding duplicated resources calls (i.e., same resource and same actions) in a test,
                        for instance, ABCDB*B**EF, swap B and F, become AFCDB*B**EB, in this case,
                        B* probability become better, B** is same, B probability become worse
                     */
                    if(isBetter != 0){
                        //find the element is not in the same position
                        val swapsloc = mutableListOf<Int>()

                        seqCur.forEachIndexed { index, restResourceCalls ->
                            if(restResourceCalls.resourceInstance.getKey() != seqPre[index].resourceInstance.getKey())
                                swapsloc.add(index)
                        }

                        assert(swapsloc.size == 2)
                        val swapF = seqCur[swapsloc[0]]
                        val swapB = seqCur[swapsloc[1]]

                        val locOfF = swapsloc[0]
                        val distance = swapF.actions.size - swapB.actions.size

                        //check F
                        if(ComparisionUtil.compare(swapsloc[0], current, swapsloc[1], previous) != 0){

                            val middles = seqCur.subList(swapsloc[0]+1, swapsloc[1]+1).map { it.resourceInstance.getAResourceKey() }
                            middles.forEach {
                                updateDependencies(swapF.resourceInstance.getAResourceKey(), mutableListOf(it),ResourceRestStructureMutator.MutationType.SWAP.toString(), (1.0/middles.size))
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

                            if(isAnyChange){
                                val seqKey = seqCur[indexOfCalls].resourceInstance.getAResourceKey()

                                val relyOn = if(changeDegree > 0){
                                    mutableListOf(swapF!!.resourceInstance.getAResourceKey())
                                }else if(changeDegree < 0){
                                    mutableListOf(swapB!!.resourceInstance.getAResourceKey())
                                }else
                                    mutableListOf(swapB!!.resourceInstance.getAResourceKey(), swapF!!.resourceInstance.getAResourceKey())

                                updateDependencies(seqKey, relyOn, ResourceRestStructureMutator.MutationType.SWAP.toString())
                            }
                        }

                        //TODO check BG

                    }

                }else{
                    //REPLACE
                    /*
                        For instance, ABCDEFG, if we replace B with H become AHCDEFG, then check CDEFG.
                        if C is worse, C rely on B; else if C is better, C rely on H; else C may not rely on B and H

                     */
                    if(isBetter != 0){
                        val mutatedIndex = (0 until seqCur.size).find { seqCur[it].resourceInstance.getKey() != seqPre[it].resourceInstance.getKey() }!!

                        val replaced = seqCur[mutatedIndex]!!
                        val replace = seqPre[mutatedIndex]!!

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

                            if(isAnyChange){
                                val seqKey = seqCur[indexOfCalls].resourceInstance.getAResourceKey()

                                val relyOn = if(changeDegree > 0){
                                    mutableListOf(replaced.resourceInstance.getAResourceKey())
                                }else if(changeDegree < 0){
                                    mutableListOf(replace.resourceInstance.getAResourceKey())
                                }else
                                    mutableListOf(replaced.resourceInstance.getAResourceKey(), replace.resourceInstance.getAResourceKey())

                                updateDependencies(seqKey, relyOn, ResourceRestStructureMutator.MutationType.REPLACE.toString())
                            }
                        }

                    }
                }
            }
            1 ->{
                //ADD
                /*
                     For instance, ABCDEFG, if we add H at 3nd position, become ABHCDEFG, then check CDEFG.
                     if C is better, C rely on H; else if C is worse, C rely on H ? ;else C may not rely on H
                */
                if(isBetter != 0){
                    val added = seqCur.find { cur -> seqPre.find { pre-> pre.resourceInstance.getKey() == cur.resourceInstance.getKey() } == null }?: return
                    val addedKey = added!!.resourceInstance.getAResourceKey()

                    val locOfAdded = seqCur.indexOf(added!!)
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

                        if(isAnyChange){
                            val seqKey = seqCur[indexOfCalls].resourceInstance.getAResourceKey()
                            updateDependencies(seqKey, mutableListOf(addedKey), ResourceRestStructureMutator.MutationType.ADD.toString())
                        }
                    }

                }
            }
            -1 ->{
                //DELETE
                /*
                     For instance, ABCDEFG, if B is deleted, become ACDEFG, then check CDEFG.
                     if C is worse, C rely on B;
                        else if C is better, C rely one B ?;
                        else C may not rely on B.

                     there is another case regarding duplicated resources calls (i.e., same resource and same actions) in a test, for instance, ABCB* (B* denotes the 2nd B), if B is deleted, become ACB*, then check CB* as before,
                     when comparing B*, B* probability achieves better performance by taking target from previous first B, so we need to compare with merged targets, i.e., B and B*.
                */
                if(isBetter != 0){
                    val delete = seqPre.find { pre -> seqCur.find { cur-> pre.resourceInstance.getKey() == cur.resourceInstance.getKey() } == null }?:return
                    val deleteKey = delete!!.resourceInstance.getAResourceKey()

                    val locOfDelete = seqPre.indexOf(delete!!)
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

                        if(isAnyChange){
                            val seqKey = seqCur[indexOfCalls].resourceInstance.getAResourceKey()
                            updateDependencies(seqKey, mutableListOf(deleteKey), ResourceRestStructureMutator.MutationType.DELETE.toString())
                        }
                    }
                }
            }
            else ->{
                throw IllegalArgumentException("apply undefined structure mutator that changed the size of resources from ${seqPre.size} to ${seqCur.size}")
            }
        }

    }

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
            found.probability = relation.probability
            if(found.additionalInfo.isBlank())
                found.additionalInfo = additionalInfo
            else if(!found.additionalInfo.contains(additionalInfo))
                found.additionalInfo += ";$additionalInfo"
        }
    }

    /**
     * this function is used to initialized ad-hoc individuals
     */
    fun createAdHocIndividuals(auth: AuthenticationInfo, adHocInitialIndividuals : MutableList<ResourceRestIndividual>){
        val sortedResources = resourceCluster.values.sortedByDescending { it.path.levels() }.asSequence()

        //GET, PATCH, DELETE
        sortedResources.forEach { ar->
            ar.actions.filter { it is RestCallAction && it.verb != HttpVerb.POST && it.verb != HttpVerb.PUT }.forEach {a->
                val call = ar.sampleOneAction(a.copy() as RestAction, randomness, config.maxTestSize)
                call.actions.forEach {a->
                    if(a is RestCallAction) a.auth = auth
                }
                adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
            }
        }

        //all POST with one post action
        sortedResources.forEach { ar->
            ar.actions.filter { it is RestCallAction && it.verb == HttpVerb.POST}.forEach { a->
                val call = ar.sampleOneAction(a.copy() as RestAction, randomness, config.maxTestSize)
                call.actions.forEach { (it as RestCallAction).auth = auth }
                adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
            }
        }

        sortedResources
                .filter { it.actions.find { a -> a is RestCallAction && a.verb == HttpVerb.POST } != null && it.postCreation.actions.size > 1   }
                .forEach { ar->
                    ar.genPostChain(randomness, config.maxTestSize)?.let {call->
                        call.actions.forEach { (it as RestCallAction).auth = auth }
                        adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
                    }
                }

        //PUT
        sortedResources.forEach { ar->
            ar.actions.filter { it is RestCallAction && it.verb == HttpVerb.PUT }.forEach {a->
                val call = ar.sampleOneAction(a.copy() as RestAction, randomness)
                call.actions.forEach { (it as RestCallAction).auth = auth }
                adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
            }
        }

        //template
        sortedResources.forEach { ar->
            ar.templates.values.filter { t-> t.template.contains(RTemplateHandler.SeparatorTemplate) }
                    .forEach {ct->
                        val call = ar.sampleRestResourceCalls(ct.template, randomness, config.maxTestSize)
                        call.actions.forEach { if(it is RestCallAction) it.auth = auth }
                        adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
                    }
        }

    }

    fun isDependencyNotEmpty() : Boolean{
        return dependencies.isNotEmpty()
    }

    /**
     * the method is invoked when mutating an individual with "ADD" resource-based structure mutator
     * in order to keep the values of individual same with previous, we bind values of new resource based on existing values
     *
     * TODO as follows
     * An example of an individual is "ABCDE", each letter is an resource call,
     * 1. at random select an resource call which has [ResourceRelatedToResources], e.g., "C"
     * 2. at random select one of its [ResourceRelatedToResources], e.g., "F"
     * 3.1 if C depends on F, then we add "F" in front of "C"
     * 3.2 if C and F are mutual relationship, it means that C and F are related to same table.
     *          In order to keep the order of creation of resources, we add F after C,
     *          but there may cause an error, e.g.,
     * 3.3 if F depends on C, then we add "F" after "C"
     */
    fun handleAddDepResource(ind : ResourceRestIndividual, maxTestSize : Int) : ResourceRestCalls?{
        val existingRs = ind.getResourceCalls().map { it.resourceInstance.getAResourceKey() }
        val candidates = dependencies.filterKeys { existingRs.contains(it) }.keys

        if(candidates.isNotEmpty()){
            val dependerPath = randomness.choose(candidates)
            //val depender = randomness.choose(ind.getResourceCalls().filter { it.resourceInstance.getAResourceKey() == dependerPath })
            val relationCandidates = dependencies[dependerPath]!!
            /*
                add self relation with a relative low probability, i.e., 20%
             */
            val relation = randomness.choose(
                        relationCandidates.filter { (if(it is SelfResourcesRelation) randomness.nextBoolean(0.2) else randomness.nextBoolean(it.probability))
                    }.run { if(isEmpty()) relationCandidates else this })

            /*
                TODO insert call at a position regarding [relation]
             */
            return resourceCluster[randomness.choose(relation.targets)]!!.sampleAnyRestResourceCalls(randomness,maxTestSize )
        }
        return null
    }


    fun handleAddResource(ind : ResourceRestIndividual, maxTestSize : Int) : ResourceRestCalls{
        val existingRs = ind.getResourceCalls().map { it.resourceInstance.getAResourceKey() }
        var candidate = randomness.choose(getResourceCluster().filterNot { r-> existingRs.contains(r.key) }.keys)
        return resourceCluster[candidate]!!.sampleAnyRestResourceCalls(randomness,maxTestSize )
    }

    /**
     *
     *  FIXME Man
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
        val first = randomness.choose(dependencies.keys)
        sampleCall(first, true, calls, maxSize)
        var sampleSize = 1
        var size = calls.sumBy { it.actions.size } + start
        val excluded = mutableListOf<String>()
        val relatedResources = mutableListOf<ResourceRestCalls>()
        excluded.add(first)
        relatedResources.add(calls.last())

        while (sampleSize < sizeOfResource && size < maxSize){
            val candidates = dependencies[first]!!.flatMap { it.targets as MutableList<String> }.filter { !excluded.contains(it) }
            if(candidates.isEmpty())
                break

            val related = randomness.choose(candidates)
            excluded.add(related)
            sampleCall(related, true, calls, size, false, if(related.isEmpty()) null else relatedResources)
            relatedResources.add(calls.last())
            size = calls.sumBy { it.actions.size } + start
        }
    }

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
            /*
                with a 50% probability, sample GET with an existing resource in db
             */
            if(hasDBHandler() && call.template.template == HttpVerb.GET.toString() && randomness.nextBoolean(0.5)){
                //val created = handleCallWithDBAction(ar, call, false, true)
                generateDbActionForCall(call, forceInsert = false, forceSelect = true)
            }
            return
        }

        assert(!ar.isIndependent())
        var candidateForInsertion : String? = null

        if(hasDBHandler() && ar.paramsToTables.isNotEmpty() && (if(forceInsert) forceInsert else randomness.nextBoolean(0.5))){
            //Insert - GET/PUT/PATCH
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
    }

    private fun existRelatedTable(call: ResourceRestCalls) : Boolean{
        if(!call.template.independent) return false
        call.actions.filter { it is RestCallAction }.find { !getRelatedTablesByAction(it as RestCallAction).isNullOrEmpty() }.apply {
            if(this != null) return true
        }
        call.actions.filter { it is RestCallAction }.find { resourceCluster[(it as RestCallAction).path.toString()]?.paramsToTables?.isNotEmpty()?:false}.apply {
            if(this != null) return true
        }
        return false
    }


    /**
     * update [resourceTables] based on test results from SUT/EM
     */
    fun updateResourceTables(resourceRestIndividual: ResourceRestIndividual, dto : TestResultsDto){

        val updateMap = mutableMapOf<String, MutableSet<String>>()

        resourceRestIndividual.seeActions().forEachIndexed { index, action ->
            // size of extraHeuristics might be less than size of action due to failure of handling rest action
            if(index < dto.extraHeuristics.size){
                val dbDto = dto.extraHeuristics[index].databaseExecutionDto

                if(action is RestCallAction){
                    val resourceId = action.path.toString()
                    val verb = action.verb.toString()

                    val update = resourceCluster[resourceId]!!.updateActionRelatedToTable(verb, dbDto, dataInDB.keys)
                    val curRelated = resourceCluster[resourceId]!!.getConfirmedRelatedTables()
                    if(update || curRelated.isNotEmpty()){
                        resourceTables.getOrPut(resourceId){ mutableSetOf()}.apply {
                            if(isEmpty() || !containsAll(curRelated)){
                                val newRelated = curRelated.filter { nr -> !this.contains(nr) }
                                updateMap.getOrPut(resourceId){ mutableSetOf()}.addAll(newRelated)
                                this.addAll(curRelated)
                            }
                        }
                    }
                }
            }
        }

        if(updateMap.isNotEmpty()){
            updateDependencyOnceResourceTableUpdate(updateMap)
        }

    }

    private fun updateDependencyOnceResourceTableUpdate(updateMap: MutableMap<String, MutableSet<String>>){

        updateMap.forEach { resource, tables ->
            val intersectTables : MutableSet<String> = mutableSetOf()
            val relatedResource = resourceTables.filter { it.key != resource }.filter { it.value.intersect(tables).also { intersect -> intersectTables.addAll(intersect) }.isNotEmpty() }

            if(relatedResource.isNotEmpty() && intersectTables.isNotEmpty()){

                val mutualRelations = dependencies
                        .getOrPut(resource){mutableListOf()}
                        .filter { it is MutualResourcesRelations && (it.targets as MutableList<String>).containsAll(relatedResource.keys)}

                if(mutualRelations.isNotEmpty()){
                    //only update confirmed map
                    mutualRelations.forEach { mu -> (mu as MutualResourcesRelations).confirmedSet.addAll(relatedResource.keys.plus(resource).toHashSet()) }
                }else{
                    val newMutualRelation = MutualResourcesRelations(relatedResource.keys.plus(resource).toList(), 1.0, intersectTables)
                    newMutualRelation.confirmedSet.addAll(relatedResource.keys.plus(resource))

                    //completely remove subsume ones
                    val remove = dependencies
                            .getOrPut(resource){mutableListOf()}
                            .filter { it is MutualResourcesRelations && relatedResource.keys.plus(resource).toHashSet().containsAll(it.targets.toHashSet())}

                    remove.forEach { r ->
                        (r.targets as MutableList<String>).forEach { res ->
                            dependencies[res]?.remove(r)
                        }
                    }

                    relatedResource.keys.plus(resource).forEach { res ->
                        dependencies.getOrPut(res){ mutableListOf()}.add(newMutualRelation)
                    }

                }
            }
        }
    }

    /**
     * @return a set of name of tables
     */
    private fun getRelatedTablesByAction(action: RestCallAction) : Set<String>?{
        return resourceCluster[action.path.toString()]?.getConfirmedRelatedTables(action)
    }

    /**
     * @return a set of name of tables
     */
    private fun getDerivedRelatedTablesByAction(action: RestCallAction) : Set<String>?{
        return resourceCluster[action.path.toString()]?.paramsToTables?.values?.flatMap { it.targets as MutableList<String> }?.toSet()
    }
    /**
     * generate dbaction for call
     */
    private fun generateDbActionForCall(call: ResourceRestCalls, forceInsert: Boolean, forceSelect: Boolean) : Boolean{
        val relatedTables = call.actions.filter { it is RestCallAction }.flatMap { getRelatedTablesByAction(it as RestCallAction)?: mutableSetOf() }.toHashSet()

        //if confirmed tables are empty, add all derived tables
        if(relatedTables.isEmpty()){
            relatedTables.addAll(call.actions.filter { it is RestCallAction }.flatMap { getDerivedRelatedTablesByAction(it as RestCallAction)?: mutableSetOf() })
        }

        val dbActions = mutableListOf<DbAction>()

        var failToLinkWithResource = false

        relatedTables.forEach { tableName->
            if(forceInsert){
                generateInserSql(tableName, dbActions)
            }else if(forceSelect){
                if(getRowInDataInDB(tableName) != null && getRowInDataInDB(tableName)!!.isNotEmpty()) generateSelectSql(tableName, dbActions)
                else failToLinkWithResource = true
            }else{
                if(getRowInDataInDB(tableName)!= null ){
                    val size = getRowInDataInDB(tableName)!!.size
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

            bindCallWithDBAction(call, dbActions)

            call.dbActions.addAll(dbActions)
        }
        return relatedTables.isNotEmpty() && !failToLinkWithResource
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

    private fun generateSelectSql(tableName : String, dbActions: MutableList<DbAction>, forceDifferent: Boolean = false, withDbAction: DbAction?=null){
        if(dbActions.map { it.table.name }.contains(tableName)) return

        assert(getRowInDataInDB(tableName) != null && getRowInDataInDB(tableName)!!.isNotEmpty())
        assert(!forceDifferent || withDbAction == null)

        val columns = if(forceDifferent && withDbAction!!.representExistingData){
            selectToDataRowDto(withDbAction, tableName)
        }else {
            randomness.choose(getRowInDataInDB(tableName)!!)
        }

        val selectDbAction = (sampler as ResourceRestSampler).sqlInsertBuilder!!.extractExistingByCols(tableName, columns)
        dbActions.add(selectDbAction)
    }

    private fun generateInserSql(tableName : String, dbActions: MutableList<DbAction>) : Boolean{
        val insertDbAction =
                (sampler as ResourceRestSampler).sqlInsertBuilder!!
                        .createSqlInsertionActionWithAllColumn(tableName)

        if(insertDbAction.isEmpty()) return false

        val pasted = mutableListOf<DbAction>()
        insertDbAction.reversed().forEach {ndb->
            val index = dbActions.indexOfFirst { it.table.name == ndb.table.name && !it.representExistingData}
            if(index == -1) pasted.add(0, ndb)
            else{
                if(pasted.isNotEmpty()){
                    dbActions.addAll(index+1, pasted)
                    pasted.clear()
                }
            }
        }

        if(pasted.isNotEmpty()){
            if(pasted.size == insertDbAction.size)
                dbActions.addAll(pasted)
            else
                dbActions.addAll(0, pasted)
        }
        return true
    }

    private fun bindCallWithDBAction(call: ResourceRestCalls, dbActions: MutableList<DbAction>, bindParamBasedOnDB : Boolean = false, excludePost : Boolean = true){
        call.actions
                .filter { (it is RestCallAction) && (!excludePost || it.verb != HttpVerb.POST) }
                .forEach {
                    /*
                    FIXME also involve additionInfo for derived tables
                     */
                    val map = getParamTableByAction(it as RestCallAction)
                    map.forEach { paramName, paramToTable ->
                        /*
                         TODO choose closest one or first?

                         if(pss.size > 1) pss[pss.size - 2] else ""
                         */
                        val table = if(paramToTable.confirmedMap.isNotEmpty()) randomness.choose(paramToTable.confirmedMap) else randomness.choose(paramToTable.targets)
                        val relatedDbAction = dbActions.plus(call.dbActions).first { db -> db.table.name == table }
                        val param = it.parameters.find { it.name == paramToTable.originalKey() }!!
                        ParamUtil.bindParam(
                                relatedDbAction,
                                param,
                                previousToken = ParamUtil.parseParams(paramToTable.additionalInfo).run {
                                    if(this.size > 1) this[this.size - 1] else ""
                                },
                                existingData = bindParamBasedOnDB || relatedDbAction.representExistingData )
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

        bindCallWithDBAction(call, dbActions.plus(call.dbActions).toMutableList(), bindParamBasedOnDB = true)


//        val paramsToBind =
//                call.actions.filter { (it is RestCallAction) && it.verb != HttpVerb.POST }
//                        .flatMap { (it as RestCallAction).parameters.map { p-> ParamRelatedToTable.getNotateKey(p.name.toLowerCase()).toLowerCase()  } }
//
//        val targets = call.resourceInstance.ar.paramsToTables.filter { paramsToBind.contains(it.key.toLowerCase())}
//
//        val tables = targets.map { it.value.targets.first().toString() }.toHashSet()
//
//        tables.forEach { tableName->
//            if(dbRelatedToTables.contains(tableName)){
//                val ps = targets.filter { it.value.targets.first().toString() == tableName }.map { it.value.additionalInfo }.toHashSet().toList()
//                val relatedDbActions = dbActions.plus(call.dbActions).first { it.table.name == tableName }
//                bindCallActionsWithDBAction(ps, call, listOf(relatedDbActions), true)
//            }
//        }

    }

    private fun getParamTableByAction(action : RestCallAction): Map<String, ParamRelatedToTable>{
        val resource = resourceCluster[action.path.toString()]!!
        return resource.paramsToTables.filter {
            action.parameters.find { p-> ParamRelatedToTable.getNotateKey(p.name.toLowerCase()).toLowerCase()  == it.key } != null }
    }



    private fun selectToDataRowDto(dbAction : DbAction, tableName : String) : DataRowDto{
        dbAction.seeGenes().forEach { assert((it is SqlPrimaryKeyGene || it is ImmutableDataHolderGene || it is SqlForeignKeyGene)) }
        val set = dbAction.seeGenes().filter { it is SqlPrimaryKeyGene }.map { ((it as SqlPrimaryKeyGene).gene as ImmutableDataHolderGene).value }.toSet()
        return randomness.choose(getRowInDataInDB(tableName)!!.filter { it.columnData.toSet().equals(set) })
    }

    private fun hasDBHandler() : Boolean = sampler is ResourceRestSampler && (sampler as ResourceRestSampler).sqlInsertBuilder!= null && config.doesInvolveDB


    private fun getRowInDataInDB(tableName: String) : MutableList<DataRowDto>?{
        dataInDB[tableName]?.let { return it}
        dataInDB[tableName.toLowerCase()]?.let { return it }
        dataInDB[tableName.toUpperCase()]?.let { return it }
        return null
    }

    /**
     * update existing data in db
     * the existing data can be applied to an sampled individual
     */
    private fun snapshotDB(){
        if(hasDBHandler()){
            (sampler as ResourceRestSampler).sqlInsertBuilder!!.extractExistingPKs(dataInDB)
        }
    }

    fun getResourceCluster() : Map<String, RestResource> {
        return resourceCluster.toMap()
    }
    fun onlyIndependentResource() : Boolean {
        return resourceCluster.values.filter{ r -> !r.isIndependent() }.isEmpty()
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

    private fun initDependencyBasedOnParamRelatedTables(){
        resourceCluster.values
                .flatMap { it.paramsToTables.values.flatMap { p2table-> p2table.targets as MutableList<String> }.toSet() }.toSet()
                .forEach { derivedTab->
                    //get probability of res -> derivedTab, we employ the max to represent the probability
                    val relatedResources = paramToSameTable(null, derivedTab)

                    val absRelatedResources = paramToSameTable(null, derivedTab, 1.0)

                    if(relatedResources.size > 1){
                        if(absRelatedResources.size > 1){
                            val mutualRelation = MutualResourcesRelations(absRelatedResources, 1.0, mutableSetOf(derivedTab))

                            absRelatedResources.forEach { res ->
                                val relations = dependencies.getOrPut(res){ mutableListOf()}
                                if(relations.find { r-> r.targets.containsAll(mutualRelation.targets) && r.additionalInfo == mutualRelation.additionalInfo } == null )
                                    relations.add(mutualRelation)
                            }
                        }

                        val rest = if(absRelatedResources.size > 1) relatedResources.filter{!absRelatedResources.contains(it)} else relatedResources

                        if(rest.size > 1){
                            for(i  in 0..(rest.size-2)){
                                val res = rest[i]
                                val prob = probOfResToTable(res, derivedTab)
                                for(j in i ..(rest.size - 1)){
                                    val relatedRes = rest[j]
                                    val relatedProb = probOfResToTable(relatedRes, derivedTab)

                                    val res2Res = MutualResourcesRelations(mutableListOf(res, relatedRes), ((prob + relatedProb)/2.0), mutableSetOf(derivedTab))

                                    dependencies.getOrPut(res){ mutableListOf()}.apply {
                                        if(find { r -> r.targets.contains(res2Res.targets) && r.additionalInfo == res2Res.additionalInfo} == null)
                                            add(res2Res)
                                    }
                                    dependencies.getOrPut(relatedRes){ mutableListOf()}.apply {
                                        if(find { r -> r.targets.contains(res2Res.targets)  && r.additionalInfo == res2Res.additionalInfo } == null)
                                            add(res2Res)
                                    }
                                }
                            }
                        }
                    }
                }
    }

//    private fun handleCallWithDBAction(ar: RestResource, call: ResourceRestCalls, forceInsert : Boolean, forceSelect : Boolean) : Boolean{
//
//        if(ar.paramsToTables.values.find { it.probability < ParserUtil.SimilarityThreshold || it.targets.isEmpty()} == null){
//            var failToLinkWithResource = false
//
//            val paramsToBind =
//                    ar.actions.filter { (it is RestCallAction) && it.verb != HttpVerb.POST }
//                            .flatMap { (it as RestCallAction).parameters.map { p-> ParamRelatedToTable.getNotateKey(p.name.toLowerCase()).toLowerCase()  } }
//
//            /*
//                key is table name
//                value is a list of information about params
//             */
//            val tableToParams = mutableMapOf<String, MutableSet<String>>()
//
//            ar.paramsToTables.forEach { t, u ->
//                if(paramsToBind.contains(t.toLowerCase())){
//                    val params = tableToParams.getOrPut(u.targets.first().toString()){ mutableSetOf() }
//                    params.add(u.additionalInfo)
//                }
//            }
//
//            snapshotDB()
//
//            val dbActions = mutableListOf<DbAction>()
//            tableToParams.keys.forEach { tableName->
//                if(forceInsert){
//                    generateInserSql(tableName, dbActions)
//                }else if(forceSelect){
//                    if(getRowInDataInDB(tableName) != null && getRowInDataInDB(tableName)!!.isNotEmpty()) generateSelectSql(tableName, dbActions)
//                    else failToLinkWithResource = true
//                }else{
//                    if(dataInDB[tableName]!= null ){
//                        val size = getRowInDataInDB(tableName)!!.size
//                        when{
//                            size < config.minRowOfTable -> generateInserSql(tableName, dbActions).apply {
//                                failToLinkWithResource = failToLinkWithResource || !this
//                            }
//                            else ->{
//                                if(randomness.nextBoolean(config.probOfSelectFromDB)){
//                                    generateSelectSql(tableName, dbActions)
//                                }else{
//                                    generateInserSql(tableName, dbActions).apply {
//                                        failToLinkWithResource = failToLinkWithResource || !this
//                                    }
//                                }
//                            }
//                        }
//                    }else
//                        failToLinkWithResource = true
//                }
//            }
//
//            if(dbActions.isNotEmpty()){
//                dbActions.removeIf { select->
//                    select.representExistingData && dbActions.find { !it.representExistingData && select.table.name == it.table.name } != null
//                }
//
//                DbActionUtils.randomizeDbActionGenes(dbActions.filter { !it.representExistingData }, randomness)
//                repairDbActions(dbActions.filter { !it.representExistingData }.toMutableList())
//
//                tableToParams.values.forEach { ps ->
//                    bindCallActionsWithDBAction(ps.toHashSet().toList(), call, dbActions)
//                }
//
//                call.dbActions.addAll(dbActions)
//            }
//            return tableToParams.isNotEmpty() && !failToLinkWithResource
//        }
//        return false
//    }


    private fun deriveRelatedTables(ar: RestResource, startWithPostIfHas : Boolean = true){
        val post = ar.postCreation.actions.firstOrNull()
        val skip = if(startWithPostIfHas && post != null && (post as RestCallAction).path.isLastElementAParameter())  1 else 0

        val missingParams = mutableListOf<String>()
        var withParam = false

        ar.tokens.values.reversed().asSequence().forEachIndexed { index, pathRToken ->
            if(index >= skip){
                if(pathRToken.isParameter){
                    missingParams.add(0, pathRToken.getKey())
                    withParam = true
                }else if(withParam){
                    missingParams.set(0, ParamUtil.generateParamId(arrayOf(pathRToken.getKey(), missingParams[0]))  )
                }
            }
        }

        val lastToken = if(missingParams.isNotEmpty()) missingParams.last()
        else if(ar.tokens.isNotEmpty()) ParamUtil.generateParamText(ar.tokens.map { it.value.getKey() })
        else null
        ar.actions
                .filter { it is RestCallAction }
                .flatMap { (it as RestCallAction).parameters }
                .filter { it !is PathParam }
                .forEach { p->
                    when(p){
                        is BodyParam -> missingParams.add(
                                (if(lastToken!=null) ParamUtil.appendParam(lastToken, "") else "") +
                                        (if(p.gene is ObjectGene && p.gene.refType != null && p.name.toLowerCase() != p.gene.refType.toLowerCase() )
                                            ParamUtil.appendParam(p.name, p.gene.refType) else p.name)
                        )
                        is QueryParam -> missingParams.add((if(lastToken!=null) ParamUtil.appendParam(lastToken, "") else "") + p.name)
                        else ->{
                            //do nothing
                        }
                    }
                }

        missingParams.forEach { pname->
            val params = ParamUtil.parseParams(pname)

            var similarity = 0.0
            var tableName = ""

            params.reversed().forEach findP@{
                dataInDB.forEach { t, u ->
                    val score = ParserUtil.stringSimilarityScore(it, t)
                    if(score > similarity){
                        similarity =score
                        tableName = t
                    }
                }
                if(similarity >= ParserUtil.SimilarityThreshold){
                    return@findP
                }
            }

            val p = params.last()
            val rt = ParamRelatedToTable(p, if(getRowInDataInDB(tableName) != null) mutableListOf(tableName) else mutableListOf(), similarity, pname)
            ar.paramsToTables.getOrPut(rt.notateKey()){
                rt
            }
        }
    }


    private fun bindCallActionsWithDBAction(ps: List<String>, call: ResourceRestCalls, dbActions : List<DbAction>, bindParamBasedOnDB : Boolean = false){
        ps.forEach { pname->
            val pss = ParamUtil.parseParams(pname)
            call.actions
                    .filter { (it is RestCallAction) && it.parameters.find { it.name.toLowerCase() == pss.last().toLowerCase() } != null }
                    .forEach { action->
                        (action as RestCallAction).parameters.filter { it.name.toLowerCase() == pss.last().toLowerCase() }
                                .forEach {param->
                                    dbActions.forEach { db->
                                        ParamUtil.bindParam(db, param,if(pss.size > 1) pss[pss.size - 2] else "", db.representExistingData || bindParamBasedOnDB )
                                    }
                                }
                    }
        }
    }

    private fun probOfResToTable(resourceKey: String, tableName: String) : Double{
        return resourceCluster[resourceKey]!!.paramsToTables.values.filter { it.targets.contains(tableName) }.map { it.probability}.max()!!
    }

    /**
     * @return resources (a list of resource id) whose parameters related to same table [tableName], and its similarity should be not less than [minSimilarity]
     */
    private fun paramToSameTable(resourceKey: String?, tableName: String, minSimilarity : Double = 0.0) : List<String>{
        return resourceCluster
                .filter { resourceKey == null || it.key == resourceKey }
                .filter {
                    it.value.paramsToTables.values
                            .find { p -> p.targets.contains(tableName) && p.probability >= minSimilarity} != null
                }.keys.toList()
    }

}