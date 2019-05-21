package org.evomaster.core.problem.rest.resource.model

import org.evomaster.client.java.controller.api.dto.database.execution.ExecutionDto
import org.evomaster.core.problem.rest.*
import org.evomaster.core.problem.rest.param.BodyParam
import org.evomaster.core.problem.rest.param.Param
import org.evomaster.core.problem.rest.param.PathParam
import org.evomaster.core.problem.rest.param.QueryParam
import org.evomaster.core.problem.rest.resource.binding.ParamUtil
import org.evomaster.core.problem.rest.resource.util.ResourceTemplateUtil
import org.evomaster.core.problem.rest.resource.model.dependency.CreationChain
import org.evomaster.core.problem.rest.resource.model.dependency.ResourceRelatedToTable
import org.evomaster.core.problem.rest.resource.parser.ParserUtil
import org.evomaster.core.problem.rest.resource.util.*
import org.evomaster.core.search.Action
import org.evomaster.core.search.gene.ObjectGene
import org.evomaster.core.search.gene.OptionalGene
import org.evomaster.core.search.service.Randomness
import org.slf4j.Logger
import org.slf4j.LoggerFactory


/**
 * @property path resource path
 * @property actions actions under the resource, with references of tables
 */
class RestResource(
        val path : RestPath,
        private val actions: MutableMap<RestAction, Boolean>
) {

    companion object {
        /**
         * control a probability to add extra patch
         */
        private const val PROB_EXTRA_PATCH = 0.8
        const val ALL_SYMBOL = "*"
        val log: Logger = LoggerFactory.getLogger(RestResource::class.java)
    }

    /**
     * [ancestors] is ordered, first is closest ancestor, and last is deepest one.
     */
    private val ancestors : MutableList<RestResource> = mutableListOf()

    /**
     * POST methods for creating available resource for the rest actions
     */
    var postCreation : CreationChain = CreationChain(mutableListOf(), false)

    val resourceToTable : ResourceRelatedToTable = ResourceRelatedToTable(path.toString())

    /**
     * key is id of param which is [getLastTokensOfPath] + [Param.name]
     * value is detailed info [ParamInfo] including
     *          e.g., whether the param is required to be bound with existing resource (i.e., POST action or table),
     */
    val paramsInfo : MutableMap<String, ParamInfo> = mutableMapOf()

    private val tokens : MutableList<PathRToken> = mutableListOf()


    /**
     * HTTP methods under the resource, including possible POST in its ancestors'
     *
     * second last means if there are post actions in its ancestors'
     * last means if there are db actions
     */
    private val verbs : Array<Boolean> = Array(ResourceTemplateUtil.arrayHttpVerbs.size + 1){false}

    /**
     * possible templates
     */
    val templates : MutableMap<String, CallsTemplate> = mutableMapOf()

    fun initAncestors(resources : List<RestResource>){
        resources.forEach {r ->
            if(!r.path.isEquivalent(this.path) && r.path.isAncestorOf(this.path))
                ancestors.add(r)
        }
    }
    /**
     * [init] requires ancestors info
     */
    fun init(withTokens : Boolean){
        initVerbs()
        initCreationPoints()
        updateTemplateSize()
        if(withTokens) initTokens()
        initParamInfo()
    }

    private fun initCreationPoints(){

        val posts = getMethods().filter { it is RestCallAction && it.verb == HttpVerb.POST}
        val post = if(posts.isEmpty()){
            chooseClosestAncestor(path, listOf(HttpVerb.POST))
        }else if(posts.size == 1){
            posts[0]
        }else null

        if(post != null){
            this.postCreation.actions.add(0, post)
            if ((post as RestCallAction).path.hasVariablePathParameters() &&
                    (!post.path.isLastElementAParameter()) ||
                    post.path.getVariableNames().size >= 2) {
                nextCreationPoints(post.path, this.postCreation.actions)
            }else
                this.postCreation.confirmComplete()
        }else{
            if(path.hasVariablePathParameters()) {
                this.postCreation.confirmIncomplete(path.toString())
            }else
                this.postCreation.confirmComplete()
        }

    }

    private fun initTokens(){
        if(path.getStaticTokens().isNotEmpty()){
            tokens.clear()
            ParserUtil.parsePathTokens(this.path, tokens)
        }
    }

    private fun nextCreationPoints(path:RestPath, points: MutableList<Action>){
        val post = chooseClosestAncestor(path, listOf(HttpVerb.POST))
        if(post != null){
            points.add(0, post)
            if (post.path.hasVariablePathParameters() &&
                    (!post.path.isLastElementAParameter()) ||
                    post.path.getVariableNames().size >= 2) {
                nextCreationPoints(post.path, points)
            }else
                this.postCreation.confirmComplete()
        }else{
            this.postCreation.confirmIncomplete(path.toString())
        }
    }

    private fun initVerbs(){
        getMethods().forEach { a->
            when((a as RestCallAction).verb){
                HttpVerb.POST -> verbs[ResourceTemplateUtil.arrayHttpVerbs.indexOf(HttpVerb.POST)] = true
                HttpVerb.GET -> verbs[ResourceTemplateUtil.arrayHttpVerbs.indexOf(HttpVerb.GET)] = true
                HttpVerb.PUT -> verbs[ResourceTemplateUtil.arrayHttpVerbs.indexOf(HttpVerb.PUT)] = true
                HttpVerb.PATCH -> verbs[ResourceTemplateUtil.arrayHttpVerbs.indexOf(HttpVerb.PATCH)] = true
                HttpVerb.DELETE ->verbs[ResourceTemplateUtil.arrayHttpVerbs.indexOf(HttpVerb.DELETE)] = true
                HttpVerb.OPTIONS ->verbs[ResourceTemplateUtil.arrayHttpVerbs.indexOf(HttpVerb.OPTIONS)] = true
                HttpVerb.HEAD -> verbs[ResourceTemplateUtil.arrayHttpVerbs.indexOf(HttpVerb.HEAD)] = true
            }
        }
        verbs[verbs.size - 1] = verbs[0]
        if (!verbs[0]){
            verbs[0] = if(ancestors.isEmpty()) false
            else ancestors.filter{ p -> p.getMethods().filter { a -> (a as RestCallAction).verb == HttpVerb.POST }.isNotEmpty() }.isNotEmpty()
        }

        ResourceTemplateUtil.initSampleSpaceOnlyPOST(verbs, templates)

        assert(templates.isNotEmpty())

    }


    fun updateActionToTables(verb : String, dto: ExecutionDto, existingTables : Set<String>) : Boolean{
        return resourceToTable.updateActionRelatedToTable(verb, dto, existingTables) || resourceToTable.actionToTables.size < getMethods().filter { it is RestCallAction }.size
    }

    fun getDerivedTables() : Set<String> = resourceToTable.derivedMap.flatMap { it.value.map { m->m.targetMatched } }.toHashSet()

    //if only get
    fun isIndependent() : Boolean{
        return verbs[ResourceTemplateUtil.arrayHttpVerbs.indexOf(HttpVerb.GET)] && verbs.filter {it}.size == 1
    }

    // if only post, the resource does not contain any independent action
    fun hasIndependentAction() : Boolean{
        return (1 until (verbs.size - 1)).find { verbs[it]} != null
    }


    private fun updateTemplateSize(){
        if(postCreation.actions.size > 1){
            templates.values.filter { it.template.contains("POST") }.forEach {
                it.size = it.size + postCreation.actions.size - 1
            }
        }
    }
    fun generateAnother(restCalls : ResourceRestCalls, randomness: Randomness, maxTestSize: Int) : ResourceRestCalls?{
        val current = restCalls.actions.map { (it as RestCallAction).verb }.joinToString(ResourceTemplateUtil.SeparatorTemplate)
        val rest = templates.filterNot { it.key == current }
        if(rest.isEmpty()) return null
        val selected = randomness.choose(rest.keys)
        return genCalls(selected,randomness, maxTestSize)

    }

    fun numOfDepTemplate() : Int{
        return templates.values.count { !it.independent }
    }

    fun numOfTemplates() : Int{
        return templates.size
    }

    private fun randomizeActionGenes(action: Action, randomness: Randomness) {
        action.seeGenes().forEach { it.randomize(randomness, false) }
        if(action is RestCallAction)
            repairRandomGenes(action.parameters)
    }

    private fun repairRandomGenes(params : List<Param>){
        if(ParamUtil.existBodyParam(params)){
            params.filter { p -> p is BodyParam }.forEach { bp->
                ParamUtil.bindParam(bp, path, path, params.filter { p -> !(p is BodyParam )}, true)
            }
        }
        params.forEach { p->
            params.find { sp -> sp != p && p.name == sp.name && p::class.java.simpleName == sp::class.java.simpleName }?.apply {
                ParamUtil.bindParam(this, path, path, mutableListOf(p))
            }
        }
    }


    fun randomRestResourceCalls(randomness: Randomness, maxTestSize: Int): ResourceRestCalls{
        val randomTemplates = templates.filter { e->
            e.value.size in 1..maxTestSize
        }.map { it.key }
        if(randomTemplates.isEmpty()) return sampleOneAction(null, randomness, maxTestSize)
        return genCalls(randomness.choose(randomTemplates), randomness, maxTestSize)
    }

    fun sampleIndResourceCall(randomness: Randomness, maxTestSize: Int) : ResourceRestCalls{
        selectTemplate({ call : CallsTemplate -> call.independent || (call.template == HttpVerb.POST.toString() && call.size > 1)}, randomness)?.let {
            return genCalls(it.template, randomness, maxTestSize, false, false)
        }
        return genCalls(HttpVerb.POST.toString(), randomness,maxTestSize)
    }


    fun sampleOneAction(verb : HttpVerb? = null, randomness: Randomness, maxTestSize: Int) : ResourceRestCalls{
        val al = if(verb != null) getActionByHttpVerb(getMethods(), verb) else randomness.choose(getMethods()).copy() as RestAction
        return sampleOneAction(al!!, randomness, maxTestSize)
    }

    fun sampleOneAction(action : RestAction, randomness: Randomness, maxTestSize: Int = 1) : ResourceRestCalls{
        val copy = action.copy()
        randomizeActionGenes(copy as RestCallAction, randomness)

        val template = templates[copy.verb.toString()]
                ?: throw IllegalArgumentException("${copy.verb} is not one of templates of ${this.path.toString()}")
        val call =  ResourceRestCalls(template, RestResourceInstance(this, copy.parameters), mutableListOf(copy))
        if(action is RestCallAction && action.verb == HttpVerb.POST){
            if(postCreation.actions.size > 1 || !postCreation.isComplete()){
                call.status = ResourceRestCalls.ResourceStatus.NOT_FOUND_DEPENDENT
            }else
                call.status = ResourceRestCalls.ResourceStatus.CREATED
        }else
            call.status = ResourceRestCalls.ResourceStatus.NO_RESOURCE

        return call
    }

    fun sampleAnyRestResourceCalls(randomness: Randomness, maxTestSize: Int) : ResourceRestCalls{
        assert(maxTestSize > 0)
        val chosen = templates.filter { it.value.size <= maxTestSize }
        if(chosen.isEmpty())
            return sampleOneAction(null,randomness, maxTestSize)
        return genCalls(randomness.choose(chosen).template,randomness, maxTestSize)
    }


    fun sampleRestResourceCalls(template: String, randomness: Randomness, maxTestSize: Int) : ResourceRestCalls{
        assert(maxTestSize > 0)
        return genCalls(template,randomness, maxTestSize)
    }

    fun genPostChain(randomness: Randomness, maxTestSize: Int) : ResourceRestCalls?{
        val template = templates["POST"]?:
            return null

        return genCalls(template.template, randomness, maxTestSize)
    }

    //TODO update postCreation accordingly
    fun genCalls(
            template : String,
            randomness: Randomness,
            maxTestSize : Int = 1,
            checkSize : Boolean = true,
            createResource : Boolean = true,
            additionalPatch : Boolean = true) : ResourceRestCalls{
        if(!templates.containsKey(template))
            throw java.lang.IllegalArgumentException("$template does not exist in ${path.toString()}")
        val ats = ResourceTemplateUtil.parseTemplate(template)
        val result : MutableList<RestAction> = mutableListOf()
        var resourceInstance : RestResourceInstance? = null

        val skipBind : MutableList<RestAction> = mutableListOf()

        var isCreated = 1
        if(createResource && ats[0] == HttpVerb.POST){
            val nonPostIndex = ats.indexOfFirst { it != HttpVerb.POST }
            val ac = getActionByHttpVerb(getMethods(), if(nonPostIndex==-1) HttpVerb.POST else ats[nonPostIndex])!!.copy() as RestCallAction
            randomizeActionGenes(ac, randomness)
            result.add(ac)
            isCreated = createResourcesFor(ac, result, maxTestSize , randomness, checkSize)
            if(result.size != postCreation.actions.size + (if(nonPostIndex == -1) 0 else 1)){
                log.warn("post creation is wrong")
            }
            val lastPost = result.last()
            resourceInstance = RestResourceInstance(this, (lastPost as RestCallAction).parameters)
            skipBind.addAll(result)
            if(nonPostIndex == -1){
                (1 until ats.size).forEach{
                    result.add(lastPost.copy().also {
                        skipBind.add(it as RestAction)
                    } as RestAction)
                }
            }else{
                if(nonPostIndex != ats.size -1){
                    (nonPostIndex + 1 until ats.size).forEach {
                        val ac = getActionByHttpVerb(getMethods(), ats[it])!!.copy() as RestCallAction
                        randomizeActionGenes(ac, randomness)
                        result.add(ac)
                    }
                }
            }

        }else{
            ats.forEach {at->
                val ac = getActionByHttpVerb(getMethods(), at)!!.copy() as RestCallAction
                randomizeActionGenes(ac, randomness)
                result.add(ac)
            }

            if(resourceInstance == null)
                resourceInstance = RestResourceInstance(this, chooseLongestPath(result, randomness).also {
                    skipBind.add(it)
                }.parameters)

        }

        if(result.size > 1)
            result.filterNot { ac -> skipBind.contains(ac) }.forEach { ac ->
                if((ac as RestCallAction).parameters.isNotEmpty()){
                    ac.bindToSamePathResolution(ac.path, resourceInstance!!.params)
                }
            }

        assert(resourceInstance!=null)
        assert(result.isNotEmpty())

        if(additionalPatch && randomness.nextBoolean(PROB_EXTRA_PATCH) &&!templates.getValue(template).independent && template.contains(HttpVerb.PATCH.toString()) && result.size + 1 <= maxTestSize){
            val index = result.indexOfFirst { (it is RestCallAction) && it.verb == HttpVerb.PATCH }
            val copy = result.get(index).copy() as RestAction
            result.add(index, copy)
        }
        val calls = ResourceRestCalls(templates[template]!!, resourceInstance!!, result)

        when(isCreated){
            1 ->{
                calls.status = ResourceRestCalls.ResourceStatus.NO_RESOURCE
            }
            0 ->{
                calls.status = ResourceRestCalls.ResourceStatus.CREATED
            }
            -1 -> {
                calls.status = ResourceRestCalls.ResourceStatus.NOT_ENOUGH_LENGTH
            }
            -2 -> {
                calls.status = ResourceRestCalls.ResourceStatus.NOT_FOUND
            }
            -3 -> {
                calls.status = ResourceRestCalls.ResourceStatus.NOT_FOUND_DEPENDENT
            }
        }

        return calls
    }

    private fun templateSelected(callsTemplate: CallsTemplate){
        templates.getValue(callsTemplate.template).times += 1
    }


    private fun selectTemplate(predicate: (CallsTemplate) -> Boolean, randomness: Randomness, chosen : Map<String, CallsTemplate>?=null, chooseLessVisit : Boolean = false) : CallsTemplate?{
        val ts = if(chosen == null) templates.filter { predicate(it.value) } else chosen.filter { predicate(it.value) }
        if(ts.isEmpty())
            return null
        return if(chooseLessVisit) ts.asSequence().sortedBy { it.value.times }.first().value.also { templateSelected(it) }
                    else randomness.choose(ts.values)
    }

    private fun getActionByHttpVerb(actions : List<RestAction>, verb : HttpVerb) : RestAction? {
        return actions.find { a -> a is RestCallAction && a.verb == verb }
    }

    private fun chooseLongestPath(actions: List<RestAction>, randomness: Randomness? = null): RestCallAction {

        if (actions.isEmpty()) {
            throw IllegalArgumentException("Cannot choose from an empty collection")
        }

        val max = actions.filter { it is RestCallAction }.asSequence().map { a -> (a as RestCallAction).path.levels() }.max()!!
        val candidates = actions.filter { a -> a is RestCallAction && a.path.levels() == max }

        if(randomness == null){
            return candidates.first() as RestCallAction
        }else
            return randomness.choose(candidates).copy() as RestCallAction
    }

    private fun chooseClosestAncestor(target: RestCallAction, verbs: List<HttpVerb>, randomness: Randomness): RestCallAction? {
        var others = sameOrAncestorEndpoints(target)
        others = hasWithVerbs(others, verbs).filter { t -> t.getName() != target.getName() }
        if(others.isEmpty()) return null
        return chooseLongestPath(others, randomness)
    }

    private fun chooseClosestAncestor(path: RestPath, verbs: List<HttpVerb>): RestCallAction? {
        val ar = if(path.toString() == this.path.toString()){
            this
        }else{
            ancestors.find { it.path.toString() == path.toString() }
        }
        ar?.let{
            val others = hasWithVerbs(it.ancestors.flatMap { it.getMethods() }.filter { it is RestCallAction } as List<RestCallAction>, verbs)
            if(others.isEmpty()) return null
            return chooseLongestPath(others)
        }
        return null
    }

    private fun hasWithVerbs(actionSeq: List<RestCallAction>, verbs: List<HttpVerb>): List<RestCallAction> {
        return actionSeq.filter { a ->
            verbs.contains(a.verb)
        }
    }

    private fun sameOrAncestorEndpoints(target: RestCallAction): List<RestCallAction> {
        if(target.path.toString() == this.path.toString()) return ancestors.flatMap { a -> a.getMethods() }.plus(getMethods()) as List<RestCallAction>
        else {
            ancestors.find { it.path.toString() == target.path.toString() }?.let {
                return it.ancestors.flatMap { a -> a.getMethods() }.plus(it.getMethods()) as List<RestCallAction>
            }
        }
        return mutableListOf()
    }


    private fun createActionFor(template: RestCallAction, target: RestCallAction, randomness: Randomness): RestCallAction {
        val restAction = template.copy() as RestCallAction
        randomizeActionGenes(restAction, randomness)
        restAction.auth = target.auth
        restAction.bindToSamePathResolution(restAction.path, target.parameters)
        return restAction
    }



    private fun independentPost() : RestAction? {
        if(!verbs.last()) return null
        val post = getActionByHttpVerb(getMethods(), HttpVerb.POST) as RestCallAction
        if(post.path.hasVariablePathParameters() &&
                (!post.path.isLastElementAParameter()) ||
                post.path.getVariableNames().size >= 2){
            return post
        }
        return null
    }

    private fun createResourcesFor(target: RestCallAction, test: MutableList<RestAction>, maxTestSize: Int, randomness: Randomness, forCheckSize : Boolean)
            : Int {

        if (!forCheckSize && test.size >= maxTestSize) {
            return -1
        }

        var template = chooseClosestAncestor(target, listOf(HttpVerb.POST), randomness)?:
                    return (if(target.verb == HttpVerb.POST) 0 else -2)

        val post = createActionFor(template, target, randomness)

        test.add(0, post)

        /*
            Check if POST depends itself on the postCreation of
            some intermediate resource
         */
        if (post.path.hasVariablePathParameters() &&
                (!post.path.isLastElementAParameter()) ||
                post.path.getVariableNames().size >= 2) {
            val dependencyCreated = createResourcesFor(post, test, maxTestSize, randomness, forCheckSize)
            if (0 != dependencyCreated) {
                return -3
            }
        }

        /*
            Once the POST is fully initialized, need to fix
            links with target
         */
        if (!post.path.isEquivalent(target.path)) {
            /*
                eg
                POST /x
                GET  /x/{id}
             */
            post.saveLocation = true
            target.locationId = post.path.lastElement()
        } else {
            /*
                eg
                POST /x
                POST /x/{id}/y
                GET  /x/{id}/y
             */
            //not going to save the position of last POST, as same as target
            post.saveLocation = false

            // the target (eg GET) needs to use the location of first POST, or more correctly
            // the same location used for the last POST (in case there is a deeper chain)
            target.locationId = post.locationId
        }

        return 0
    }

    fun isAnyAction() : Boolean{
        verbs.forEach {
            if (it) return true
        }
        return false
    }

    fun getName() : String = path.toString()

//    fun existDuplicateParamName() : Boolean{
//        duplicatePathParamName?.let { return it }
//        duplicatePathParamName = path.getVariableNames().size == path.getVariableNames().toSet().size
//        return duplicatePathParamName!!
//    }

    fun isPartOfStaticTokens(text : String) : Boolean{
        return tokens.any { token ->
            token.equals(text)
        }
    }

    fun getMethods() : List<RestAction> = actions.keys.toList()
    fun allParamsInfoUpdate() : Boolean = actions.none { !it.value }

    fun addMethod(action: RestAction){
        actions.getOrPut(action){false}
    }

    /******************** manage param *************************/
    fun getParamId(params: List<Param>, param : Param) : String = "${param::class.java.simpleName}:${getParamName(params, param)}"

    private fun getParamName(params: List<Param>, param : Param) : String = ParamUtil.appendParam(getLastTokensOfPath(params, param), param.name)

    private fun getLastTokensOfPath() : String = tokens.last().segment

    fun getLastTokensOfPath(params: List<Param>, param : Param) : String {
        if(param !is PathParam) return tokens.last().segment
        val index = params.filter { it is PathParam && it.name == param.name}.indexOf(param)
        return tokens.filter { it.isParameter && ComparisionUtil.compareString(it.originalText, param.name) }[index].segment
    }

    fun getSegmentLevel(params: List<Param>, param : Param): Int{
        if(getSegments().size == 1) return 0
        val index  = getSegments().indexOf(getLastTokensOfPath(params, param))
        return getSegments().size -1 - index
    }

    fun getSegmentLevel(segment: String): Int{
        if(getSegments().size == 1) return 0
        val index  = getSegments().indexOf(segment)
        return getSegments().size -1 - index
    }

    fun getFullTokensOfPath() : String = ParamUtil.generateParamId(path.getStaticTokens().toTypedArray())

    fun getSegments() : List<String>{
        val result = mutableListOf<String>()
        tokens.filter { it.isParameter }.forEach {
            if(!result.contains(it.segment)) result.add(it.segment)
        }
        if(result.isEmpty() && getFullTokensOfPath().isNotBlank()) result.add(getFullTokensOfPath())
        return result
    }

    fun getRefTypes() : Set<String>{
        return paramsInfo.filter {  it.value.referParam is BodyParam && it.value.referParam.gene is ObjectGene && (it.value.referParam.gene as ObjectGene).refType != null}.map {
            ((it.value.referParam as BodyParam).gene as ObjectGene).refType!!
        }.toSet()
    }


    fun anyParameterChanged(action : RestCallAction) : Boolean{
        val target = getMethods().find { it.getName() == action.getName() }
                ?: throw IllegalArgumentException("cannot find the action ${action.getName()} in the resource ${getName()}")
        return action.parameters.size != (target as RestCallAction).parameters.size
    }

    fun updateAdditionalParams(action: RestCallAction) : Map<String, ParamInfo>?{
        val target = (getMethods().find { it is RestCallAction && it.getName() == action.getName() }
                ?: throw IllegalArgumentException("cannot find the action ${action.getName()} in the resource ${getName()}")) as RestCallAction
        if(actions.getValue(target)) return null

        actions.replace(target, true)

        val additionParams = action.parameters.filter { p-> paramsInfo[getParamId(action.parameters, p)] == null}
        if(additionParams.isEmpty()) return null
        return additionParams.map { p-> Pair(getParamId(action.parameters, p), initParamInfo(action.verb, action.parameters, p)) }.toMap()
    }

    fun updateAdditionalParam(action: RestCallAction, param: Param) : ParamInfo{
        return initParamInfo(action.verb, action.parameters, param).also { it.fromAdditionInfo = true }
    }

    private fun initParamInfo(){
        paramsInfo.clear()

        /*
         parameter that is required to bind with post action, or row of tables
         1) path parameter in the middle of the path, i.e., /A/{a}/B/{b}, {a} is required to bind
         2) GET, DELETE, PATCH, PUT(-prob), if the parameter refers to "id", it is required to bind, in most case, the parameter is either PathParam or QueryParam
         3) other parameter, it is not necessary to bind, but it helps if it is bound.
                e.g., Request to get a list of data whose value is less than "parameter", if bind with an existing data, the requests make more sentence than a random data
         */

        if (tokens.isEmpty()) return
        getMethods().forEach { a ->
            if(a is RestCallAction){
                a.parameters.forEach{p->
                    initParamInfo(a.verb, a.parameters, p)
                }
            }
        }
    }

    private fun initParamInfo(verb: HttpVerb, params: List<Param>, param: Param) : ParamInfo{
        /*
            in any case, PathParam is required to be bound
         */
        val segment = getLastTokensOfPath(params, param)
        val key = getParamId(params,param)

        val isRequiredToBind = (param is PathParam) || (param is QueryParam && param.gene !is OptionalGene)

        val paramInfo = paramsInfo.getOrPut(key){
                ParamInfo(
                        param.name,
                        segment,
                        isRequiredToBind,
                        findPathParamInPostChain(verb.toString(), param, segment),
                        param)
            }

        if (isRequiredToBind) paramInfo.isRequiredToBind = isRequiredToBind

        paramInfo.involvedAction.add(verb)
        return paramInfo
    }

    private fun findPathParamInPostChain(verb: String, param : Param, segment: String) : Boolean{
        return postCreation.actions.subList(0, postCreation.actions.size - (if(verb == HttpVerb.POST.toString()) 1 else 0)).reversed().any { post->
            val ar = if((post as RestCallAction).path.toString() == path.toString()) this else ancestors.find { it.path.toString() == post.path.toString() }!!
            post.parameters.any {postParam->
                ParserUtil.stringSimilarityScore(ar.getLastTokensOfPath(post.parameters, postParam), segment) > 0.5
                        &&(postParam.name == param.name ||
                        ((postParam is BodyParam) && (postParam.gene is ObjectGene) && postParam.gene.fields.any { f-> ComparisionUtil.compareString(f.name, param.name) }))
            }
        }
    }

    class ParamInfo(
            val name : String,
            val previousSegment: String,
            var isRequiredToBind : Boolean,
            val hasInPostChain: Boolean,
            val referParam : Param,
            val involvedAction : MutableSet<HttpVerb> = mutableSetOf(),
            var fromAdditionInfo : Boolean = false
    )
}