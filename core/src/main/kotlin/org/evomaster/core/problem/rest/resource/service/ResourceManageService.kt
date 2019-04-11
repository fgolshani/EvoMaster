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
import org.evomaster.core.problem.rest.param.PathParam
import org.evomaster.core.problem.rest.param.QueryParam
import org.evomaster.core.problem.rest.resource.ResourceRestIndividual
import org.evomaster.core.problem.rest.resource.model.RestAResource
import org.evomaster.core.problem.rest.resource.model.RestResourceCalls
import org.evomaster.core.problem.rest.resource.model.dependency.MutualResourcesRelations
import org.evomaster.core.problem.rest.resource.model.dependency.ParamRelatedToTable
import org.evomaster.core.problem.rest.resource.model.dependency.ResourceRelatedToResources
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
    private val resourceCluster : MutableMap<String, RestAResource> = mutableMapOf()
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

    fun initAbstractResources(actionCluster : MutableMap<String, Action>) {
        actionCluster.values.forEach { u ->
            if (u is RestCallAction) {
                val resource = resourceCluster.getOrPut(u.path.toString()) {
                    RestAResource(u.path.copy(), mutableListOf())
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
                if(ar.paramsToTables.isEmpty())
                    deriveRelatedTables(ar,false)
            }
        }

        if(config.probOfEnablingResourceDependencyHeuristics > 0.0)
            initDependency()

    }

    private fun initDependency(){
        //1. based on resourceTables to identify mutual relations among resources
        updateDependency()

        //2. for each resource, identify relations based on derived table
        resourceCluster.values
                .flatMap { it.paramsToTables.values.flatMap { p2table-> p2table.targets as MutableList<String> }.toSet() }.toSet()
                .forEach { derivedTab->
                    //get probability of res -> derivedTab, we employ the max to represent the probability
                    val relatedResources = paramToSameTable(null, derivedTab)

                    val absRelatedResources = paramToSameTable(null, derivedTab, 1.0)

                    if(relatedResources.size > 1){
                        if(absRelatedResources.size > 1){
                            val mutualRelation = MutualResourcesRelations(absRelatedResources, 1.0, derivedTab)

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

                                    val res2Res = MutualResourcesRelations(mutableListOf(res, relatedRes), ((prob + relatedProb)/2.0), derivedTab)

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

    private fun updateDependency(){
        resourceTables.values.flatten().toSet().forEach { tab->
            val mutualResources = resourceTables.filter { it.value.contains(tab) }.map { it.key }.toHashSet().toList()

            if(mutualResources.isNotEmpty() && mutualResources.size > 1){
                val mutualRelation = MutualResourcesRelations(mutualResources, 1.0, tab)

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
                //SWAP, MODIFY, REPLACE
                if(seqPre.map { it.resource.getAResourceKey() }.toList() == seqCur.map { it.resource.getAResourceKey() }.toList()){
                    //MODIFY
                    /*
                        For instance, ABCDEFG, if we replace B with another resource instance, then check CDEFG.
                        if C is worse/better, C rely on B, else C may not rely on B, i.e., the changes of B cannot affect C.
                     */
                    if(isBetter != 0){
                        val modified = seqCur.find { ec -> seqPre.find { ep -> ep.resource.getKey() != ec.resource.getKey() } != null  }
                        val locOfModified = seqCur.indexOf(modified)

                        var actionIndex = seqCur.mapIndexed { index, restResourceCalls ->
                            if(index < locOfModified) restResourceCalls.actions.size
                            else 0
                        }.sum()

                        ((locOfModified + 1) until seqCur.size).forEach { indexOfCalls ->
                            var isAnyChange = false
                            seqCur[indexOfCalls].actions.forEach {
                                actionIndex += 1
                                val actionA = actionIndex
                                isAnyChange = isAnyChange || compare(actionA, current, actionIndex, previous) !=0
                            }

                            if(isAnyChange){
                                //seqPre[indexOfCalls] depends on added
                                val seqKey = seqPre[indexOfCalls].resource.getAResourceKey()
                                val depModified = ResourceRelatedToResources(listOf(seqKey), mutableListOf(modified!!.resource.getAResourceKey()))

                                val found = dependencies.getOrPut(seqKey){ mutableListOf()}.find { it.targets.contains(modified!!.resource.getAResourceKey()) }
                                if (found == null) dependencies[seqKey]!!.add(depModified)
                                else found.probability = 1.0
                            }
                        }
                    }


                }else if(seqPre.map { it.resource.getAResourceKey() }.toSet() == seqCur.map { it.resource.getAResourceKey() }.toSet()){
                    //SWAP
                    /*
                        For instance, ABCDEFG, if we swap B and F, become AFCDEBG, then check FCDE (do not include B!).
                        if F is worse, F may rely on {C, D, E, B}
                        if C is worse, C rely on B; else if C is better, C rely on F; else C may not rely on B and F
                     */
                    if(isBetter != 0){
                        //find the element is not in the same position
                        val swaps = seqCur.filterIndexed { index, ec ->
                            seqPre.indexOfFirst { ep->
                                ep.resource.getAResourceKey() == ec.resource.getAResourceKey()
                            } != index
                        }
                        assert(swaps.size == 2)
                        val swapF = swaps[0]
                        val swapB = swaps[1]

                        val locOfF = seqCur.indexOf(swapF)

                        val distance = swapF.actions.size - swapB.actions.size

                        var actionIndex = seqCur.mapIndexed { index, restResourceCalls ->
                            if(index < locOfF) restResourceCalls.actions.size
                            else 0
                        }.sum()

                        ( (locOfF + 1) until seqCur.indexOf(swapB) ).forEach { indexOfCalls ->
                            var isAnyChange = false
                            var changeDegree = 0
                            seqCur[indexOfCalls].actions.forEach {
                                actionIndex += 1
                                val actionA = actionIndex + distance
                                isAnyChange = isAnyChange || compare(actionA, current, actionIndex, previous).also { r-> changeDegree += r } !=0
                            }

                            if(isAnyChange){
                                val seqKey = seqPre[indexOfCalls].resource.getAResourceKey()

                                val relyOn = if(changeDegree > 0){
                                    mutableListOf(swapF!!.resource.getAResourceKey())
                                }else if(changeDegree < 0){
                                    mutableListOf(swapB!!.resource.getAResourceKey())
                                }else
                                    mutableListOf(swapB!!.resource.getAResourceKey(), swapF!!.resource.getAResourceKey())

                                val depReplace = ResourceRelatedToResources(listOf(seqKey), relyOn)

                                val found = dependencies.getOrPut(seqKey){ mutableListOf()}.find { it.targets.contains(relyOn) }

                                if (found == null) dependencies[seqKey]!!.add(depReplace)
                                else found.probability = 1.0
                            }
                        }

                    }

                }else{
                    //REPLACE
                    /*
                        For instance, ABCDEFG, if we replace B with H become AHCDEFG, then check CDEFG.
                        if C is worse, C rely on B; else if C is better, C rely on H; else C may not rely on B and H
                     */
                    if(isBetter != 0){
                        val replaced = seqCur.find { cur -> seqPre.find { pre-> pre.resource.getAResourceKey() == cur.resource.getAResourceKey() } == null }

                        val replace = seqPre.find { pre -> seqCur.find { cur-> pre.resource.getAResourceKey() == cur.resource.getAResourceKey() } == null }

                        val locOfReplaced = seqCur.indexOf(replaced)
                        val distance = locOfReplaced - seqPre.indexOf(replace)

                        var actionIndex = seqCur.mapIndexed { index, restResourceCalls ->
                            if(index < locOfReplaced) restResourceCalls.actions.size
                            else 0
                        }.sum()

                        ( (locOfReplaced + 1) until seqCur.size ).forEach { indexOfCalls ->
                            var isAnyChange = false
                            var changeDegree = 0
                            seqCur[indexOfCalls].actions.forEach {
                                actionIndex += 1
                                val actionA = actionIndex + distance
                                isAnyChange = isAnyChange || compare(actionA, current, actionIndex, previous).also { r-> changeDegree += r } !=0
                            }

                            if(isAnyChange){
                                val seqKey = seqPre[indexOfCalls].resource.getAResourceKey()

                                val relyOn = if(changeDegree > 0){
                                    mutableListOf(replaced!!.resource.getAResourceKey())
                                }else if(changeDegree < 0){
                                    mutableListOf(replace!!.resource.getAResourceKey())
                                }else
                                    mutableListOf(replaced!!.resource.getAResourceKey(), replace!!.resource.getAResourceKey())

                                val depReplace = ResourceRelatedToResources(listOf(seqKey), relyOn)

                                val found = dependencies.getOrPut(seqKey){ mutableListOf()}.find { it.targets.contains(relyOn) }

                                if (found == null) dependencies[seqKey]!!.add(depReplace)
                                else found.probability = 1.0
                            }
                        }

                    }
                }
            }
            1 ->{
                //ADD
                /*
                     For instance, ABCDEFG, if we add H at 3nd position, become ABHCDEFG, then check CDEFG.
                     if C is better, C rely on H; else if C is worse, ??? ;else C may not rely on H
                */
                if(isBetter == 1){
                    val added = seqCur.find { cur -> seqPre.find { pre-> pre.resource.getAResourceKey() == cur.resource.getAResourceKey() } == null }
                    val addedKey = added!!.resource.getAResourceKey()

                    val locOfAdded = seqCur.indexOf(added!!)
                    var actionIndex = seqCur.mapIndexed { index, restResourceCalls ->
                        if(index < locOfAdded) restResourceCalls.actions.size
                        else 0
                    }.sum()

                    val distance = added!!.actions.size

                    (locOfAdded until seqPre.size).forEach { indexOfCalls ->
                        var isAnyChange = false

                        seqPre[indexOfCalls].actions.forEach {
                            actionIndex += 1 //actionB
                            val actionA = actionIndex + distance
                            isAnyChange = isAnyChange || compare(actionA, current, actionIndex, previous)!=0
                        }

                        if(isAnyChange){
                            //seqPre[indexOfCalls] depends on added
                            val seqKey = seqPre[indexOfCalls].resource.getAResourceKey()
                            val depAdded = ResourceRelatedToResources(listOf(seqKey), mutableListOf(addedKey))

                            val found = dependencies.getOrPut(seqKey){ mutableListOf()}.find { it.targets.contains(addedKey) }
                            if (found == null) dependencies[seqKey]!!.add(depAdded)
                            else found.probability = 1.0
                        }
                    }
                }
            }
            -1 ->{
                //DELETE
                /*
                     For instance, ABCDEFG, if we delete B, become ACDEFG, then check CDEFG.
                     if C is worse, C rely on B; else if C is better, ??? ; else C may not rely on B.
                */
                if(isBetter == -1){
                    val delete = seqPre.find { pre -> seqCur.find { cur-> pre.resource.getAResourceKey() == cur.resource.getAResourceKey() } == null }
                    val deleteKey = delete!!.resource.getAResourceKey()

                    val locOfDelete = seqPre.indexOf(delete!!)
                    var actionIndex = seqPre.mapIndexed { index, restResourceCalls ->
                        if(index < locOfDelete) restResourceCalls.actions.size
                        else 0
                    }.sum()

                    val distance = delete!!.actions.size

                    ((locOfDelete + 1) until seqPre.size).forEach { indexOfCalls ->
                        var isAnyChange = false

                        seqPre[indexOfCalls].actions.forEach {
                            actionIndex += 1 //actionB
                            val actionA = actionIndex - distance
                            isAnyChange = isAnyChange || compare(actionA, current, actionIndex, previous)!=0
                        }

                        if(isAnyChange){
                            //seqPre[indexOfCalls] depends on added
                            val seqKey = seqPre[indexOfCalls].resource.getAResourceKey()
                            val depDelete = ResourceRelatedToResources(listOf(seqKey), mutableListOf(deleteKey))

                            val found = dependencies.getOrPut(seqKey){ mutableListOf()}.find { it.targets.contains(deleteKey) }
                            if (found == null) dependencies[seqKey]!!.add(depDelete)
                            else found.probability = 1.0
                        }
                    }
                }else if(isBetter == 1){
                    // is it possible to make it better
                    TODO("not support yet")
                }
            }
            else ->{
                TODO("not support yet")
            }
        }

    }

    /**
     *  is the performance of [actionA] better than the performance [actionB]?
     */
    private fun compare(actionA : Int, eviA : EvaluatedIndividual<ResourceRestIndividual>, actionB: Int, eviB : EvaluatedIndividual<ResourceRestIndividual>) : Int{
        val alistHeuristics = eviA.fitness.getViewOfData().filter { it.value.actionIndex == actionA }
        val blistHeuristics = eviB.fitness.getViewOfData().filter { it.value.actionIndex == actionB }

        //whether actionA reach more
        if(alistHeuristics.size > blistHeuristics.size) return 1
        else if(alistHeuristics.size < blistHeuristics.size) return -1

        //whether actionA reach new
        if(alistHeuristics.filter { !blistHeuristics.containsKey(it.key) }.isNotEmpty()) return 1
        else if(blistHeuristics.filter { !alistHeuristics.containsKey(it.key) }.isNotEmpty()) return -1

        val targets = alistHeuristics.keys.plus(blistHeuristics.keys).toHashSet()

        targets.forEach { t->
            val ta = alistHeuristics[t]
            val tb = blistHeuristics[t]

            if(ta != null && tb != null){
                if(ta.distance > tb.distance)
                    return 1
                else if(ta.distance < tb.distance)
                    return -1
            }
        }

        return 0
    }

    private fun probOfResToTable(resourceKey: String, tableName: String) : Double{
        return resourceCluster[resourceKey]!!.paramsToTables.values.filter { it.targets.contains(tableName) }.map { it.probability}.max()!!
    }

    private fun paramToSameTable(resourceKey: String?, tableName: String, minSimilarity : Double = 0.0) : List<String>{
        return resourceCluster
                .filter { resourceKey!= null || it.key != resourceKey }
                .filter {
                    it.value.paramsToTables.values
                        .find { p -> p.targets.contains(tableName) && p.probability >= minSimilarity} != null
                }.keys.toList()
    }

    /**
     * this function is used to initialized ad-hoc individuals
     */
    fun createAdHocIndividuals(auth: AuthenticationInfo, adHocInitialIndividuals : MutableList<ResourceRestIndividual>){
        val sortedResources = resourceCluster.values.sortedByDescending { it.tokens.size }.asSequence()

        //GET, PATCH, DELETE
        sortedResources.forEach { ar->
            ar.actions.filter { it is RestCallAction && it.verb != HttpVerb.POST && it.verb != HttpVerb.PUT }.forEach {a->
                val call = ar.sampleOneAction(a.copy() as RestAction, randomness, config.maxTestSize)
                call.actions.forEach {a->
                    if(a is RestCallAction) a.auth = auth
                }
                adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE))
            }
        }

        //all POST with one post action
        sortedResources.forEach { ar->
            ar.actions.filter { it is RestCallAction && it.verb == HttpVerb.POST}.forEach { a->
                val call = ar.sampleOneAction(a.copy() as RestAction, randomness, config.maxTestSize)
                call.actions.forEach { (it as RestCallAction).auth = auth }
                adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE))
            }
        }

        sortedResources
                .filter { it.actions.find { a -> a is RestCallAction && a.verb == HttpVerb.POST } != null && it.postCreation.actions.size > 1   }
                .forEach { ar->
                    ar.genPostChain(randomness, config.maxTestSize)?.let {call->
                        call.actions.forEach { (it as RestCallAction).auth = auth }
                        call.doesCompareDB = hasDBHandler()
                        adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE))
                    }
                }

        //PUT
        sortedResources.forEach { ar->
            ar.actions.filter { it is RestCallAction && it.verb == HttpVerb.PUT }.forEach {a->
                val call = ar.sampleOneAction(a.copy() as RestAction, randomness)
                call.actions.forEach { (it as RestCallAction).auth = auth }
                adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE))
            }
        }

        //template
        sortedResources.forEach { ar->
            ar.templates.values.filter { t-> t.template.contains(RTemplateHandler.SeparatorTemplate) }
                    .forEach {ct->
                        val call = ar.sampleRestResourceCalls(ct.template, randomness, config.maxTestSize)
                        call.actions.forEach { if(it is RestCallAction) it.auth = auth }
                        adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE))
                    }
        }

    }

    fun isDependencyNotEmpty() : Boolean{
        return dependencies.isNotEmpty()
    }

    fun handleAddDepResource(ind : ResourceRestIndividual, maxTestSize : Int) : RestResourceCalls?{
        val existingRs = ind.getResourceCalls().map { it.resource.ar.path.toString() }

        val candidates = dependencies.filterKeys { existingRs.contains(it) }.keys
        if(candidates.isNotEmpty()){
            val candidate =randomness.choose(dependencies[randomness.choose(candidates)]!!.flatMap { it.targets as MutableList<String> })
            return resourceCluster[candidate]!!.sampleAnyRestResourceCalls(randomness,maxTestSize )
        }
        return null
    }


    fun handleAddResource(ind : ResourceRestIndividual, maxTestSize : Int) : RestResourceCalls{
        val existingRs = ind.getResourceCalls().map { it.resource.ar.path.toString() }
        var candidate = randomness.choose(getResourceCluster().filterNot { r-> existingRs.contains(r.key) }.keys)
        return resourceCluster[candidate]!!.sampleAnyRestResourceCalls(randomness,maxTestSize )
    }


    fun sampleRelatedResources(calls : MutableList<RestResourceCalls>, sizeOfResource : Int, maxSize : Int) {
        var start = - calls.sumBy { it.actions.size }

        val first = randomness.choose(dependencies.keys)
        sampleCall(first, true, calls, maxSize)
        var sampleSize = 1
        var size = calls.sumBy { it.actions.size } + start
        val excluded = mutableListOf<String>()
        val relatedResources = mutableListOf<RestResourceCalls>()
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

    fun sampleCall(resourceKey: String, doesCreateResource: Boolean, calls : MutableList<RestResourceCalls>, size : Int, forceInsert: Boolean = false, bindWith : MutableList<RestResourceCalls>? = null){
        val ar = resourceCluster[resourceKey]
                ?: throw IllegalArgumentException("resource path $resourceKey does not exist!")

        if(!doesCreateResource ){
            val call = ar.sampleIndResourceCall(randomness,size)
            calls.add(call)
            //TODO shall we control the probability to sample GET with an existing resource.
            if(hasDBHandler() && call.template.template == HttpVerb.GET.toString() && randomness.nextBoolean(0.5)){
                val created = handleCallWithDBAction(ar, call, false, true)
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
            if(call.status != RestResourceCalls.ResourceStatus.CREATED
                    || checkIfDeriveTable(call)
                    || candidateForInsertion != null){

                call.doesCompareDB = true
                /*
                    derive possible db, and bind value according to db
                */
                val created = handleCallWithDBAction(ar, call, forceInsert, false)
                if(!created){
                    //TODO MAN record the call when postCreation fails
                }
            }else{
                call.doesCompareDB = (!call.template.independent) && (resourceTables[ar.path.toString()] == null)
            }
        }

        if(bindWith != null){
            bindCallWithFront(call, bindWith)
        }
    }

    fun bindCallWithFront(call: RestResourceCalls, front : MutableList<RestResourceCalls>){
        val targets = front.flatMap { it.actions.filter {a -> a is RestCallAction }}

        call.actions
                .filter { it is RestCallAction }
                .forEach { a ->
                    (a as RestCallAction).parameters.forEach { p->
                        targets.forEach { ta->
                            ParamUtil.bindParam(p, a.path, (ta as RestCallAction).path, ta.parameters)
                        }
                    }
                }

        // if post repeats in the following action, it might need to remove
        front.flatMap { it.dbActions }.apply {
            if(isNotEmpty())
                bindCallWithOtherDBAction(call, this.toMutableList())
        }
    }

    private fun checkIfDeriveTable(call: RestResourceCalls) : Boolean{
        if(!call.template.independent) return false

        call.actions.first().apply {
            if (this is RestCallAction){
                if(this.parameters.isNotEmpty()) return true
            }
        }
        return false
    }

    private fun deriveRelatedTables(ar: RestAResource, startWithPostIfHas : Boolean = true){
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
            val rt = ParamRelatedToTable(p, if(dataInDB[tableName] != null) mutableListOf(tableName) else mutableListOf(), similarity, pname)
            ar.paramsToTables.getOrPut(rt.notateKey()){
                rt
            }
        }
    }

    private fun handleCallWithDBAction(ar: RestAResource, call: RestResourceCalls, forceInsert : Boolean, forceSelect : Boolean) : Boolean{
        //TODO whether we need to check the similarity of the param name at this stage?
        if(ar.paramsToTables.values.find { it.probability < ParserUtil.SimilarityThreshold || it.targets.isEmpty()} == null){
            var failToLinkWithResource = false

            val paramsToBind =
                    ar.actions.filter { (it is RestCallAction) && it.verb != HttpVerb.POST }
                            .flatMap { (it as RestCallAction).parameters.map { p-> ParamRelatedToTable.getNotateKey(p.name.toLowerCase()).toLowerCase()  } }

            /*
                key is table name
                value is a list of information about params
             */
            val tableToParams = mutableMapOf<String, MutableSet<String>>()

            ar.paramsToTables.forEach { t, u ->
                if(paramsToBind.contains(t.toLowerCase())){
                    val params = tableToParams.getOrPut(u.targets.first().toString()){ mutableSetOf() }
                    params.add(u.additionalInfo)
                }
            }

            snapshotDB()

            val dbActions = mutableListOf<DbAction>()
            tableToParams.keys.forEach { tableName->
                if(forceInsert){
                    generateInserSql(tableName, dbActions)
                }else if(forceSelect){
                    if(dataInDB[tableName] != null && dataInDB[tableName]!!.isNotEmpty()) generateSelectSql(tableName, dbActions)
                    else failToLinkWithResource = true
                }else{
                    if(dataInDB[tableName]!= null ){
                        val size = dataInDB[tableName]!!.size
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
                dbActions.removeIf { select->
                    select.representExistingData && dbActions.find { !it.representExistingData && select.table.name == it.table.name } != null
                }

                DbActionUtils.randomizeDbActionGenes(dbActions.filter { !it.representExistingData }, randomness)
                repairDbActions(dbActions.filter { !it.representExistingData }.toMutableList())

                tableToParams.values.forEach { ps ->
                    bindCallActionsWithDBAction(ps.toHashSet().toList(), call, dbActions)
                }
                call.dbActions.addAll(dbActions)
            }
            return tableToParams.isNotEmpty() && !failToLinkWithResource
        }
        return false
    }

    fun repairRestResourceCalls(call: RestResourceCalls)  : Boolean{
        call.repairGenesAfterMutation()
        if(hasDBHandler() && call.dbActions.isNotEmpty()){
            val key = call.resource.ar.path.toString()
            val ar = resourceCluster[key]
                    ?: throw IllegalArgumentException("resource path $key does not exist!")
            call.dbActions.clear()
            handleCallWithDBAction(ar, call, true, false)
        }
        return true
    }

    private fun generateSelectSql(tableName : String, dbActions: MutableList<DbAction>, forceDifferent: Boolean = false, withDbAction: DbAction?=null){
        if(dbActions.map { it.table.name }.contains(tableName)) return

        assert(dataInDB[tableName] != null && dataInDB[tableName]!!.isNotEmpty())
        assert(!forceDifferent || withDbAction == null)

        val columns = if(forceDifferent && withDbAction!!.representExistingData){
            selectToDataRowDto(withDbAction, tableName)
        }else {
            randomness.choose(dataInDB[tableName]!!)
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

    private fun bindCallWithOtherDBAction(call : RestResourceCalls, dbActions: MutableList<DbAction>){
        val dbRelatedToTables = dbActions.map { it.table.name }

        val paramsToBind =
                call.actions.filter { (it is RestCallAction) && it.verb != HttpVerb.POST }
                        .flatMap { (it as RestCallAction).parameters.map { p-> ParamRelatedToTable.getNotateKey(p.name.toLowerCase()).toLowerCase()  } }
        val targets = call.resource.ar.paramsToTables.filter { paramsToBind.contains(it.key.toLowerCase())}

        val tables = targets.map { it.value.targets.first().toString() }.toHashSet()


        tables.forEach { tableName->
            if(dbRelatedToTables.contains(tableName)){
                val ps = targets.filter { it.value.targets.first().toString() == tableName }.map { it.value.additionalInfo }.toHashSet().toList()
                val relatedDbActions = dbActions.first { it.table.name == tableName }

                //bind data based on previous actions, so set bindParamBasedOnDB true
                bindCallActionsWithDBAction(ps, call, listOf(relatedDbActions), true)

                //carefully remove dbaction, since it might be used by other table insertion.
                call.dbActions.removeIf { db ->
                    db.table.name == tableName
                }
            }
        }
    }

    //TODO handle SqlAutoIncrementGene
    private fun bindCallActionsWithDBAction(ps: List<String>, call: RestResourceCalls, dbActions : List<DbAction>, bindParamBasedOnDB : Boolean = false){
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

    private fun selectToDataRowDto(dbAction : DbAction, tableName : String) : DataRowDto{
        dbAction.seeGenes().forEach { assert((it is SqlPrimaryKeyGene || it is ImmutableDataHolderGene || it is SqlForeignKeyGene)) }
        val set = dbAction.seeGenes().filter { it is SqlPrimaryKeyGene }.map { ((it as SqlPrimaryKeyGene).gene as ImmutableDataHolderGene).value }.toSet()
        return randomness.choose(dataInDB[tableName]!!.filter { it.columnData.toSet().equals(set) })
    }

    //tables
    private fun hasDBHandler() : Boolean = sampler is ResourceRestSampler && (sampler as ResourceRestSampler).sqlInsertBuilder!= null && config.doesInvolveDB

    fun snapshotDB(){
        if(hasDBHandler()){
            (sampler as ResourceRestSampler).sqlInsertBuilder!!.extractExistingPKs(dataInDB)
        }
    }

    /*
        two purposes of the comparision:
        1) at the starting, check if data can be modified (if the rest follows the semantic, it should be added) by POST action of resources.
            based on the results, relationship between resource and table can be built.
        2) with the search, the relationship (resource -> table) can be further
     */
    fun compareDB(call : RestResourceCalls){

        if(hasDBHandler()){
            assert(call.doesCompareDB)

            if((sampler as ResourceRestSampler).sqlInsertBuilder != null){

                val previous = dataInDB.toMutableMap()
                snapshotDB()

                /*
                    using PKs, check whether any row is changed
                    TODO further check whether any value of row is changed, e.g., pk keeps same, but one value of other columns is changed (PATCH)
                 */
                val ar = call.resource.ar
                val tables = resourceTables.getOrPut(ar.path.toString()){ mutableSetOf() }
                if(isDBChanged(previous, dataInDB)){
                    tables.addAll(tableChanged(previous, dataInDB))
                    tables.addAll(tableChanged(dataInDB, previous))
                }
                if(call.dbActions.isNotEmpty()){
                    tables.addAll(call.dbActions.map { it.table.name }.toHashSet())
                }
                updateDependency()
            }
        }
    }

    private fun tableChanged(
            a : MutableMap<String, MutableList<DataRowDto>>,
            b : MutableMap<String, MutableList<DataRowDto>>
    ) : MutableSet<String>{

        val result = mutableSetOf<String>()
        a.forEach { t, u ->
            if(!b.containsKey(t)) result.add(t)
            else{
                b[t]!!.apply {
                    if(size != u.size) result.add(t)
                    else {
                        val bcolContent = this.map { it.columnData.joinToString() }
                        loop@for(con in u){
                            if(!bcolContent.contains(con.columnData.joinToString())){
                                result.add(t)
                                break@loop
                            }
                        }
                    }
                }
            }
        }

        return result
    }

    private fun isDBChanged(previous : MutableMap<String, MutableList<DataRowDto>>,
                  current : MutableMap<String, MutableList<DataRowDto>>) : Boolean{
        if(previous.size != current.size) return true
        for(entry in current){
            val pre = previous[entry.key] ?: return true
            if(entry.value.size != pre!!.size) return true
            val preData = pre!!.map { it.columnData.joinToString() }
            for(cdata in entry.value){
                if(!preData.contains(cdata.columnData.joinToString())) return true
            }
        }
        return false
    }

    fun getResourceCluster() : Map<String, RestAResource> {
        return resourceCluster.toMap()
    }
    fun onlyIndependentResource() : Boolean {
        return resourceCluster.values.filter{ r -> !r.isIndependent() }.isEmpty()
    }

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
}