package org.evomaster.core.problem.rest.resource.util

import org.evomaster.core.problem.rest.resource.ResourceRestIndividual
import org.evomaster.core.search.EvaluatedIndividual

class ComparisionUtil {
    companion object {


        fun compare(actionName : String, eviA : EvaluatedIndividual<ResourceRestIndividual>, eviB : EvaluatedIndividual<ResourceRestIndividual>) : Int{
            val actionAs = mutableListOf<Int>()
            val actionBs = mutableListOf<Int>()
            eviA.individual.seeActions().forEachIndexed { index, action ->
                if(action.getName() == actionName)
                    actionAs.add(index)
            }

            eviB.individual.seeActions().forEachIndexed { index, action ->
                if(action.getName() == actionName)
                    actionBs.add(index)
            }

            return compare(actionAs, eviA, actionBs, eviB)
        }

        /**
         *  is the performance of [actionA] better than the performance [actionB]?
         */
        fun compare(actionA : Int, eviA : EvaluatedIndividual<ResourceRestIndividual>, actionB: Int, eviB : EvaluatedIndividual<ResourceRestIndividual>) : Int{
            return compare(mutableListOf(actionA), eviA, mutableListOf(actionB), eviB)
        }

        private fun compare(actionA : MutableList<Int>, eviA : EvaluatedIndividual<ResourceRestIndividual>, actionB: MutableList<Int>, eviB : EvaluatedIndividual<ResourceRestIndividual>) : Int{
            val alistHeuristics = eviA.fitness.getViewOfData().filter { actionA.contains(it.value.actionIndex) }
            val blistHeuristics = eviB.fitness.getViewOfData().filter { actionB.contains(it.value.actionIndex) }

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
    }
}