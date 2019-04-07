package org.evomaster.core.search

import org.evomaster.core.search.gene.Gene
import org.evomaster.core.search.impact.ImpactOfGene
import org.evomaster.core.search.impact.ImpactOfStructure
import org.evomaster.core.search.impact.ImpactUtil
import org.evomaster.core.search.service.Sampler
import org.evomaster.core.search.tracer.TraceableElement
import org.evomaster.core.search.tracer.TrackOperator
import kotlin.math.absoluteValue

/**
 * EvaluatedIndividual allows to track its evolution.
 * Note that tracking EvaluatedIndividual can be enabled by set EMConfig.enableTrackEvaluatedIndividual true.
 */
class EvaluatedIndividual<T>(val fitness: FitnessValue,
                             val individual: T,
                             /**
                              * Note: as the test execution could had been
                              * prematurely stopped, there might be less
                              * results than actions
                              */
                             val results: List<out ActionResult>,
                             trackOperator: TrackOperator? = null,
                             tracking : MutableList<EvaluatedIndividual<T>>? = null,
                             undoTracking : MutableList<EvaluatedIndividual<T>>? = null)
    : TraceableElement(trackOperator,  tracking, undoTracking) where T : Individual {

    init{
        if(individual.seeActions().size < results.size){
            throw IllegalArgumentException("Less actions than results")
        }
        if(tracking!=null && tracking.isNotEmpty() && tracking.first().trackOperator !is Sampler<*>){
            throw IllegalArgumentException("First tracking operator must be sampler")
        }
    }

    /**
     * [hasImprovement] represents if [this] helps to improve Archive, e.g., reach new target.
     */
    var hasImprovement = false
    /**
     * key -> action name : gene name
     * value -> impact degree
     */
    val impactsOfGenes : MutableMap<String, ImpactOfGene> = mutableMapOf()

    /**
     * key -> action names that join with ";"
     * value -> impact degree
     */
    val impactsOfStructure : MutableMap<String, ImpactOfStructure> = mutableMapOf()

    val reachedTargets : MutableMap<Int, Double> = mutableMapOf()
    /**
     * [hasImprovement] represents if [this] helps to improve Archive, e.g., reach new target.
     */
    var hasImprovement = false

    fun copy(): EvaluatedIndividual<T> {
        return EvaluatedIndividual(
                fitness.copy(),
                individual.copy() as T,
                results.map(ActionResult::copy),
                trackOperator
        )
    }

    /**
     * Note: if a test execution was prematurely stopped,
     * the number of evaluated actions would be lower than
     * the total number of actions
     */
    fun evaluatedActions() : List<EvaluatedAction>{

        val list: MutableList<EvaluatedAction> = mutableListOf()

        val actions = individual.seeActions()

        (0 until results.size).forEach { i ->
            list.add(EvaluatedAction(actions[i], results[i]))
        }

        return list
    }

    override fun copy(withTrack: Boolean): EvaluatedIndividual<T> {

        when(withTrack){
            false-> return copy()
            else ->{
                /**
                 * if the [getTrack] is null, which means the tracking option is attached on individual not evaluated individual
                 */
                getTrack()?:return EvaluatedIndividual(
                        fitness.copy(),
                        individual.copy(withTrack) as T,
                        results.map(ActionResult::copy),
                        trackOperator
                )

                return forceCopyWithTrack()
            }
        }
    }

    fun forceCopyWithTrack(): EvaluatedIndividual<T> {
        return EvaluatedIndividual(
                fitness.copy(),
                individual.copy() as T,
                results.map(ActionResult::copy),
                trackOperator?:individual.trackOperator,
                getTrack()?.map { (it as EvaluatedIndividual<T> ).copy() }?.toMutableList()?: mutableListOf(),
                getUndoTracking()?.map { it.copy()}?.toMutableList()?: mutableListOf()
        )
    }


    override fun next(trackOperator: TrackOperator, next: TraceableElement): EvaluatedIndividual<T>? {
        val copyTraces = getTrack()?.map { (it as EvaluatedIndividual<T> ).copy() }?.toMutableList()?: mutableListOf()
        copyTraces.add(this.copy())
        val copyUndoTraces = getUndoTracking()?.map {(it as EvaluatedIndividual<T>).copy()}?.toMutableList()?: mutableListOf()


        return  EvaluatedIndividual(
                (next as EvaluatedIndividual<T>).fitness.copy(),
                next.individual.copy(false) as T,
                next.results.map(ActionResult::copy),
                trackOperator,
                copyTraces,
                copyUndoTraces
        )
    }

    override fun getUndoTracking(): MutableList<EvaluatedIndividual<T>>? {
        if(super.getUndoTracking() == null) return null
        return super.getUndoTracking() as MutableList<EvaluatedIndividual<T>>
    }

    fun initImpacts(){
        getTrack()?.apply {
            assert(size == 0)
        }
        individual.seeActions().forEach { a->
            a.seeGenes().forEach { g->
                val id = ImpactUtil.generateId(a, g)
                impactsOfGenes.putIfAbsent(id, ImpactOfGene(id, 0.0))
            }
        }
        /*
            empty action?
         */
        if(individual.seeActions().isEmpty()){
            individual.seeGenes().forEach {
                val id = ImpactUtil.generateId(it)
                impactsOfGenes.putIfAbsent(id, ImpactOfGene(id, 0.0))
            }
        }

        getTrack()?.apply {
            assert(size == 0)
        }

        if(individual.seeActions().isNotEmpty()){
            val id = ImpactUtil.generateId(individual)
            impactsOfStructure.putIfAbsent(id, ImpactOfStructure(id, 0.0))
        }

        fitness.getViewOfData().forEach { t, u ->
            reachedTargets.put(t, u.distance)
        }
    }

    /**
     * compare current with latest
     */
    fun updateImpactOfGenes(inTrack : Boolean){
        assert(getTrack() != null)
        assert(getUndoTrack() != null)

        if(inTrack) assert(getTrack()!!.isNotEmpty())
        else assert(getUndoTrack()!!.isNotEmpty())

        val previous = if(inTrack) getTrack()!!.last() as EvaluatedIndividual<T> else this
        val next = if(inTrack) this else getUndoTrack()!!.last() as EvaluatedIndividual<T>

        val isAnyOverallImprovement = updateReachedTargets(fitness)
        val comparedFitness = next.fitness.computeFitnessScore() - previous.fitness.computeFitnessScore()

        compareWithLatest(next, previous, (isAnyOverallImprovement || comparedFitness != 0.0), inTrack)
    }

    private fun compareWithLatest(next : EvaluatedIndividual<T>, previous : EvaluatedIndividual<T>, isAnyChange : Boolean, isNextThis : Boolean){
        val delta = (next.fitness.computeFitnessScore() - previous.fitness.computeFitnessScore()).absoluteValue

        if(impactsOfStructure.isNotEmpty()){

            val nextSeq = ImpactUtil.generateId(next.individual)
            val previousSeq = ImpactUtil.generateId(previous.individual)

            /*
                a sequence of an individual is used to present its structure,
                    the degree of impact of the structure is evaluated as the max fitness value.
                In this case, we only find best and worst structure.
             */
            val structureId = if(isNextThis)nextSeq else previousSeq
            if(nextSeq != previousSeq){
                val impact = impactsOfStructure.getOrPut(structureId){ImpactOfStructure(structureId, 0.0)}
                val degree = (if(isNextThis)next else previous).fitness.computeFitnessScore()
                if( degree > impact.degree)
                    impact.degree = degree
            }
            impactsOfStructure[structureId]!!.countImpact(isAnyChange)
        }

        val geneIds = generateMap(next.individual)
        val latestIds = generateMap(previous.individual)

        //following is to detect genes to mutate, and update the impact of the detected genes.
        /*
            same gene, but the value is different.
            In this case, we increase the degree impact based on
                absoluteValue of difference between next and previous regarding fitness, i.e., delta
         */
        geneIds.keys.intersect(latestIds.keys).forEach { keyId->
            val curGenes = ImpactUtil.extractGeneById(next.individual.seeActions(), keyId)
            val latestGenes = ImpactUtil.extractGeneById(previous.individual.seeActions(), keyId)

            curGenes.forEach { cur ->
                latestGenes.find { ImpactUtil.isAnyChange(cur, it) }?.let {
                    impactsOfGenes.getOrPut(keyId){
                        ImpactOfGene(keyId, 0.0)
                    }.apply{
                        if(isAnyChange) increaseDegree(delta)
                        countImpact(isAnyChange)
                    }
                }
            }

        }

        /*
            new gene, we increase its impact by delta
         */
        geneIds.filter { !latestIds.containsKey(it.key) }.forEach { t, _ ->
            impactsOfGenes.getOrPut(t){
                ImpactOfGene(t, 0.0)
            }.apply{
                if(isAnyChange) increaseDegree(delta)
                countImpact(isAnyChange)
            }
        }

        /*
           removed gene, we increase its impact by delta
        */
        latestIds.filter { !geneIds.containsKey(it.key) }.forEach { t, _ ->
            impactsOfGenes.getOrPut(t){
                ImpactOfGene(t, 0.0)
            }.apply{
                if(isAnyChange) increaseDegree(delta)
                countImpact(isAnyChange)
            }
        }
    }


    private fun generateMap(individual: T) : MutableMap<String, MutableList<Gene>>{
        val map = mutableMapOf<String, MutableList<Gene>>()
        if(individual.seeActions().isNotEmpty()){
            individual.seeActions().forEachIndexed {i, a ->
                a.seeGenes().forEach {g->
                    val genes = map.getOrPut(ImpactUtil.generateId(a, g)){ mutableListOf()}
                    genes.add(g)
                }
            }
        }else{
            individual.seeGenes().forEach {g->
                val genes = map.getOrPut(ImpactUtil.generateId(g)){ mutableListOf()}
                genes.add(g)
            }
        }

        return map
    }

    private fun updateReachedTargets(fitness: FitnessValue) : Boolean{
        var isAnyOverallImprovement = false
        fitness.getViewOfData().forEach { t, u ->
            var previous = reachedTargets[t]
            if(previous == null){
                isAnyOverallImprovement = true
                previous = 0.0
                reachedTargets.put(t, previous)
            }
            isAnyOverallImprovement = isAnyOverallImprovement || u.distance > previous
            if(u.distance > previous)
                reachedTargets[t] = u.distance
        }
        return isAnyOverallImprovement
    }

    override fun getUndoTrack(): MutableList<EvaluatedIndividual<T>>? {
        return getUndoTrack() as MutableList<EvaluatedIndividual<T>>
    }

}