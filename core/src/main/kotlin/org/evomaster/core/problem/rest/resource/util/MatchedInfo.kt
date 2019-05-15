package org.evomaster.core.problem.rest.resource.util

import kotlin.math.min

class MatchedInfo(val matched : String, var similarity : Double, var inputIndicator : Int = 0, var outputIndicator : Int = 0){

    fun modifySimilarity(times : Double = 0.9){
        similarity *= times
        if (similarity > 1.0) similarity = 1.0
    }

    fun setMax(){
        similarity = 1.0
    }

    fun setMin(){
        similarity = min(similarity, ParserUtil.SimilarityThreshold)
    }
}
