package org.evomaster.core.problem.rest.resource.model

import org.evomaster.core.problem.rest.param.Param
import org.evomaster.core.problem.rest.param.PathParam
import org.evomaster.core.problem.rest.param.QueryParam

class RestResourceInstance (val ar : RestResource, val params: List<Param>){

    fun equals(others : List<Param>) : Boolean{//FIXME
        return ar.path.resolve(params) == ar.path.resolve(others)
    }

    fun getKey() : String{
        return "${ar.path.resolve(params)};${params.filter { it !is PathParam && it !is QueryParam}.sortedBy { it.name }.map { it.gene.getValueAsRawString() }.joinToString(",")}"
    }

    fun getAResourceKey() : String = ar.path.toString()

    fun copy() : RestResourceInstance{//keep same ar, but copy new param
        return RestResourceInstance(ar, params.map { param -> param.copy() })
    }
}