package org.evomaster.core.problem.rest.resource.model

import org.evomaster.core.problem.rest.*
import org.evomaster.core.problem.rest.auth.AuthenticationInfo
import org.evomaster.core.problem.rest.param.BodyParam
import org.evomaster.core.problem.rest.param.Param
import org.evomaster.core.problem.rest.param.PathParam
import org.evomaster.core.problem.rest.resource.ResourceRestIndividual
import org.evomaster.core.problem.rest.resource.model.dependency.CreationChain
import org.evomaster.core.problem.rest.resource.model.dependency.ParamRelatedToTable
import org.evomaster.core.problem.rest.resource.util.ParamUtil
import org.evomaster.core.problem.rest.resource.util.ParserUtil
import org.evomaster.core.problem.rest.resource.util.RTemplateHandler
import org.evomaster.core.search.Action
import org.evomaster.core.search.service.Randomness
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * @property path resource path
 * @property actions actions under the resource, with references of tables
 */
class RestAResource {

    val path : RestPath
    val actions: MutableList<RestAction>

    companion object {
        //may move to EMConfig
        private const val PROB_EXTRA_PATCH = 0.8
        val log: Logger = LoggerFactory.getLogger(RestAResource::class.java)
    }

    constructor(path: RestPath, actions: MutableList<RestAction>){
        this.path = path
        this.actions = actions
        initTokens()
        val action = getActionByHttpVerb(actions, HttpVerb.GET)
    }


    /**
     * [ancestors] is ordered, first is closest ancestor, and last is deepest one.
     */
    private val ancestors : MutableList<RestAResource> = mutableListOf()

    var postCreation : CreationChain = CreationChain(mutableListOf(), false)

    val paramsToTables : MutableMap<String, ParamRelatedToTable> = mutableMapOf()

    val tokens : MutableMap<String, PathRToken> = mutableMapOf()

    /**
     * HTTP methods under the resource, including possible POST in its ancestors'
     *
     * second last means if there are post actions in its ancestors'
     * last means if there are db actions
     */
    val verbs : Array<Boolean> = Array(RTemplateHandler.arrayHttpVerbs.size + 1){false}

    /**
     * possible templates
     */
    val templates : MutableMap<String, CallsTemplate> = mutableMapOf()

    val countTempVisiting = false

    fun init(){
        initVerbs()
        initCreationPoints()
        updateTemplateSize()
    }

    private fun initCreationPoints(){

        val posts = actions.filter { it is RestCallAction && it.verb == HttpVerb.POST}
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

    fun initTokens(){
        if(path.getStaticTokens().isNotEmpty()){
            tokens.clear()
            ParserUtil.parsePathTokens(this.path.toString(), tokens)
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

    fun initAncestors(resources : List<RestAResource>){
        resources.forEach {r ->
            if(!r.path.isEquivalent(this.path) && r.path.isAncestorOf(this.path))
                ancestors.add(r)
        }
    }

    private fun initVerbs(){
        actions.forEach { a->
            when((a as RestCallAction).verb){
                HttpVerb.POST -> verbs[RTemplateHandler.arrayHttpVerbs.indexOf(HttpVerb.POST)] = true
                HttpVerb.GET -> verbs[RTemplateHandler.arrayHttpVerbs.indexOf(HttpVerb.GET)] = true
                HttpVerb.PUT -> verbs[RTemplateHandler.arrayHttpVerbs.indexOf(HttpVerb.PUT)] = true
                HttpVerb.PATCH -> verbs[RTemplateHandler.arrayHttpVerbs.indexOf(HttpVerb.PATCH)] = true
                HttpVerb.DELETE ->verbs[RTemplateHandler.arrayHttpVerbs.indexOf(HttpVerb.DELETE)] = true
                HttpVerb.OPTIONS ->verbs[RTemplateHandler.arrayHttpVerbs.indexOf(HttpVerb.OPTIONS)] = true
                HttpVerb.HEAD -> verbs[RTemplateHandler.arrayHttpVerbs.indexOf(HttpVerb.HEAD)] = true
            }
        }
        verbs[verbs.size - 1] = verbs[0]
        if (!verbs[0]){
            verbs[0] = if(ancestors.isEmpty()) false
            else ancestors.filter{ p -> p.actions.filter { a -> (a as RestCallAction).verb == HttpVerb.POST }.isNotEmpty() }.isNotEmpty()
        }

        RTemplateHandler.initSampleSpaceOnlyPOST(verbs, templates)

        assert(templates.isNotEmpty())

    }

    //if only get
    fun isIndependent() : Boolean{
        return paramsToTables.isEmpty() && verbs[RTemplateHandler.arrayHttpVerbs.indexOf(HttpVerb.GET)] && verbs.filter {it}.size == 1
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
    fun generateAnother(calls : RestResourceCalls, randomness: Randomness, maxTestSize: Int) : RestResourceCalls?{
        val current = calls.actions.map { (it as RestCallAction).verb }.joinToString(RTemplateHandler.SeparatorTemplate)
        val rest = templates.filterNot { it.key == current }
        if(rest.isEmpty()) return null
        val selected = randomness.choose(rest.keys)
        return genCalls(selected,randomness, maxTestSize)

    }

    fun initialIndividuals(str : Int, auth : AuthenticationInfo, adHocInitialIndividuals : MutableList<ResourceRestIndividual>, randomness: Randomness, maxTestSize: Int)  {
        when(str){
            1 -> {
                getTemplates().forEach { t ->
                    val res = genCalls(t,randomness, maxTestSize, true)
                    res.actions.forEach {
                        (it as RestCallAction).auth = auth
                    }
                    adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(res), SampleType.SMART_RESOURCE))
                }


            }

            2-> actions.forEach {a->
                val res = sampleOneAction(a, randomness, maxTestSize)
                res.actions.forEach {
                    (it as RestCallAction).auth = auth
                }
                adHocInitialIndividuals.add(ResourceRestIndividual( mutableListOf(res), SampleType.SMART_RESOURCE))
            }

            3-> {
                //add dependent actions
                independentPost()?.let { apost->
                    val res = genCalls(HttpVerb.POST.toString(),randomness, maxTestSize, false, false)
                    res.actions.forEach {
                        (it as RestCallAction).auth = auth
                    }
                    adHocInitialIndividuals.add(ResourceRestIndividual( mutableListOf(res), SampleType.SMART_RESOURCE))
                }
            }
        }
    }

    fun numOfDepTemplate() : Int{
        return templates.values.count { !it.independent }
    }

    fun numOfTemplates() : Int{
        return templates.size
    }

    fun getTemplates() : MutableList<String> {
        return templates.keys.toMutableList()
    }
    fun randomizeActionGenes(action: Action, randomness: Randomness) {
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


    fun randomRestResourceCalls(randomness: Randomness, maxTestSize: Int): RestResourceCalls{
        val randomTemplates = templates.filter { e->
            e.value.size in 1..maxTestSize
        }.map { it.key }
        if(randomTemplates.isEmpty()) return sampleOneAction(null, randomness, maxTestSize)
        return genCalls(randomness.choose(randomTemplates), randomness, maxTestSize)
    }

    fun sampleIndResourceCall(randomness: Randomness, maxTestSize: Int) : RestResourceCalls{
        selectTemplate({ call : CallsTemplate -> call.independent || (call.template == HttpVerb.POST.toString() && call.size > 1)}, randomness)?.let {
            return genCalls(it.template, randomness, maxTestSize, false, false)
        }
        return genCalls(HttpVerb.POST.toString(), randomness,maxTestSize)
    }


    fun sampleOneAction(verb : HttpVerb? = null, randomness: Randomness, maxTestSize: Int) : RestResourceCalls{
        val al = if(verb != null) getActionByHttpVerb(actions, verb) else randomness.choose(actions).copy() as RestAction
        return sampleOneAction(al!!, randomness, maxTestSize)
    }

    fun sampleOneAction(action : RestAction, randomness: Randomness, maxTestSize: Int = 1) : RestResourceCalls{
        val copy = action.copy()
        randomizeActionGenes(copy as RestCallAction, randomness)

        val template = templates[copy.verb.toString()]
                ?: throw IllegalArgumentException("${copy.verb} is not one of templates of ${this.path.toString()}")
        val call =  RestResourceCalls(template, RestResource(this, copy.parameters), mutableListOf(copy))
        if(action is RestCallAction && action.verb == HttpVerb.POST){
            if(postCreation.actions.size > 1 || !postCreation.isComplete()){
                call.status = RestResourceCalls.ResourceStatus.NOT_FOUND_DEPENDENT
            }else
                call.status = RestResourceCalls.ResourceStatus.CREATED
        }else
            call.status = RestResourceCalls.ResourceStatus.NOT_EXISTING

        return call
    }

    fun sampleAnyRestResourceCalls(randomness: Randomness, maxTestSize: Int) : RestResourceCalls{
        assert(maxTestSize > 0)
        val chosen = templates.filter { it.value.size <= maxTestSize }
        if(chosen.isEmpty())
            return sampleOneAction(null,randomness, maxTestSize)
        return genCalls(randomness.choose(chosen).template,randomness, maxTestSize)
    }


    fun sampleRestResourceCalls(template: String, randomness: Randomness, maxTestSize: Int) : RestResourceCalls{
        assert(maxTestSize > 0)
        return genCalls(template,randomness, maxTestSize)
    }

    fun genPostChain(randomness: Randomness, maxTestSize: Int) : RestResourceCalls?{
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
            additionalPatch : Boolean = true) : RestResourceCalls{
        if(!templates.containsKey(template))
            throw java.lang.IllegalArgumentException("$template does not exist in ${path.toString()}")
        val ats = RTemplateHandler.parseTemplate(template)
        val result : MutableList<RestAction> = mutableListOf()
        var resource : RestResource? = null

        val skipBind : MutableList<RestAction> = mutableListOf()

        var isCreated = 1
        if(createResource && ats[0] == HttpVerb.POST){
            val nonPostIndex = ats.indexOfFirst { it != HttpVerb.POST }
            val ac = getActionByHttpVerb(actions, if(nonPostIndex==-1) HttpVerb.POST else ats[nonPostIndex])!!.copy() as RestCallAction
            randomizeActionGenes(ac, randomness)
            result.add(ac)
            isCreated = createResourcesFor(ac, result, maxTestSize , randomness, checkSize && (!templates.getValue(template).sizeAssured))

            if(checkSize && (!templates.getValue(template).sizeAssured) && isCreated == 0 ){
                if(result.size != if(nonPostIndex == -1) 1 else (nonPostIndex + 1)){
                    templates.filter { it.key.contains(HttpVerb.POST.toString()) }.forEach{temp->
                        temp.value.size = result.size + ats.size - (if(nonPostIndex == -1) 1 else (nonPostIndex + 1))
                        temp.value.sizeAssured = true
                    }
                }else{
                    templates.getValue(template).sizeAssured = true
                }
            }
            val lastPost = result.last()
            resource = RestResource(this, (lastPost as RestCallAction).parameters)
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
                        val ac = getActionByHttpVerb(actions, ats[it])!!.copy() as RestCallAction
                        randomizeActionGenes(ac, randomness)
                        result.add(ac)
                    }
                }
            }

        }else{
            ats.forEach {at->
                val ac = getActionByHttpVerb(actions, at)!!.copy() as RestCallAction
                randomizeActionGenes(ac, randomness)
                result.add(ac)
            }

            if(resource == null)
                resource = RestResource(this, chooseLongestPath(result, randomness).also {
                    skipBind.add(it)
                }.parameters)

            if(checkSize){
                templates.getValue(template).sizeAssured = (result.size  == templates.getValue(template).size)
            }
        }

        if(result.size > 1)
            result.filterNot { ac -> skipBind.contains(ac) }.forEach { ac ->
                if((ac as RestCallAction).parameters.isNotEmpty()){
                    ac.bindToSamePathResolution(ac.path, resource!!.params)
                }
            }

        assert(resource!=null)
        assert(result.isNotEmpty())

        if(additionalPatch && randomness.nextBoolean(PROB_EXTRA_PATCH) &&!templates.getValue(template).independent && template.contains(HttpVerb.PATCH.toString()) && result.size + 1 <= maxTestSize){
            val index = result.indexOfFirst { (it is RestCallAction) && it.verb == HttpVerb.PATCH }
            val copy = result.get(index).copy() as RestAction
            result.add(index, copy)
        }
        val calls = RestResourceCalls(templates[template]!!, resource!!, result)

        when(isCreated){
            1 ->{
                calls.status = RestResourceCalls.ResourceStatus.NOT_EXISTING
            }
            0 ->{
                calls.status = RestResourceCalls.ResourceStatus.CREATED
            }
            -1 -> {
                calls.status = RestResourceCalls.ResourceStatus.NOT_ENOUGH_LENGTH
            }
            -2 -> {
                calls.status = RestResourceCalls.ResourceStatus.NOT_FOUND
            }
            -3 -> {
                calls.status = RestResourceCalls.ResourceStatus.NOT_FOUND_DEPENDENT
            }
        }

        return calls
    }

    private fun templateSelected(callsTemplate: CallsTemplate){
        if(countTempVisiting)
            templates.getValue(callsTemplate.template).times += 1
    }


    private fun selectTemplate(predicate: (CallsTemplate) -> Boolean, randomness: Randomness, chosen : Map<String, CallsTemplate>?=null) : CallsTemplate?{
        val ts = if(chosen == null) templates.filter { predicate(it.value) } else chosen.filter { predicate(it.value) }
        if(ts.isEmpty())
            return null
        var template =  if(countTempVisiting) ts.asSequence().sortedBy { it.value.times }.first().value
                    else randomness.choose(ts.values)
        templateSelected(template)
        return template
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
            val others = hasWithVerbs(it.ancestors.flatMap { it.actions }.filter { it is RestCallAction } as List<RestCallAction>, verbs)
            if(others.isEmpty()) return null
            return chooseLongestPath(others)
        }
        return null
    }

    private fun hasWithVerbs(actions: List<RestCallAction>, verbs: List<HttpVerb>): List<RestCallAction> {
        return actions.filter { a ->
            verbs.contains(a.verb)
        }
    }

    private fun sameOrAncestorEndpoints(target: RestCallAction): List<RestCallAction> {
        if(target.path.toString() == this.path.toString()) return ancestors.flatMap { a -> a.actions }.plus(actions) as List<RestCallAction>
        else {
            ancestors.find { it.path.toString() == target.path.toString() }?.let {
                return it.ancestors.flatMap { a -> a.actions }.plus(it.actions) as List<RestCallAction>
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
        val post = getActionByHttpVerb(actions, HttpVerb.POST) as RestCallAction
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
}