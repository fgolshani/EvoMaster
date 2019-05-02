package org.evomaster.core.problem.rest.resource.model.dependency

import edu.stanford.nlp.time.SUTime
import org.evomaster.core.problem.rest.resource.db.SQLKey

/**
 * key relies on target
 */
open class RelatedTo(
        private val key: String,
        val targets : MutableList<out Any>,
        var probability: Double,
        var additionalInfo : String = ""
){

    init {
        assert(targets.isNotEmpty())
    }
    companion object {
        private const val DIRECT_DEPEND =  "$->$"

        fun generateKeyForMultiple(keys : List<String>) : String = if(keys.isEmpty()) "" else if( keys.size == 1)  keys.first() else "{${keys.joinToString(",")}}"

        fun parseMultipleKey(key : String) : List<String> {
            if(key.startsWith("{") && key.endsWith("}")){
                return key.substring(1, key.length - 1).split(",")
            }
            return listOf(key)
        }
    }
    open fun notateKey() : String = key
    open fun originalKey() : String = key

    open fun getTargetsName () : String = generateKeyForMultiple(targets.map { it.toString() })
    open fun getName() : String = "${notateKey()}$DIRECT_DEPEND${getTargetsName()}"
}

/**
 * @property key is name of param in a resource path
 * @property targets each element is a name of table
 */
class ParamRelatedToTable (
        key: String,
        target: MutableList<String>,
        probability: Double,
        info: String=""
):RelatedTo(key, target, probability, info){

    companion object {
        fun getNotateKey(paramName : String): String = "PARM:$paramName"
    }

    override fun notateKey() : String = getNotateKey(originalKey())
}

class ActionRelatedToTable(
        key: String,
        val tableWithFields: MutableMap<String, MutableList<AccessTable>> = mutableMapOf()
): RelatedTo(key, tableWithFields.keys.toMutableList(), 1.0, "") {

    fun updateTableWithFields(results : Map<String, Set<String>>, method: SQLKey) {
        var doesUpdateTarget = false
        results.forEach { t, u ->
            doesUpdateTarget = doesUpdateTarget || tableWithFields.containsKey(t)
            tableWithFields.getOrPut(t){ mutableListOf() }.run {
                var target = find { it.method == method }
                if (target == null){
                    target = AccessTable(method, t, mutableSetOf())
                    this.add(target)
                }
                target!!.field.addAll(u)
            }
        }
        if(doesUpdateTarget){
            targets.clear()
            (targets as MutableList<String>).addAll(tableWithFields.keys)
        }
    }

    //fun getTableWithFields() : Map<String, MutableList<AccessTable>> = tableWithFields.toMap()

    fun doesSubsume(tables : List<String>, subsumeThis : Boolean) : Boolean{
        return if(subsumeThis) tables.toHashSet().containsAll(tableWithFields.keys)
                else tableWithFields.keys.containsAll(tables)
    }

    class AccessTable(val method : SQLKey, val table : String, val field : MutableSet<String>)
}

/**
 *  @param path is a list of path(s), which can be parsed with [RelatedTo.parseMultipleKey]
 *  @param target is a list of paths of related rest resources
 */
open class ResourceRelatedToResources(
        path : List<String>,
        target: MutableList<String>,
        probability: Double = 1.0,
        info: String = ""
) : RelatedTo(generateKeyForMultiple(path), target, probability, info){

    init {
        assert(path.isNotEmpty())
    }
    //override fun getTargetsName () : String = generateKeyForMultiple(targets.map { (it as RestAResource).path.toString() })

}
/**
 * @param info provides information that supports the resources are mutual relations of each other,
 *          e.g., the resources are related to same table, and set [info] name of the table.
 */
class MutualResourcesRelations(mutualResources: List<String>, probability: Double, info:String)
    : ResourceRelatedToResources(mutualResources, mutualResources.toMutableList(), probability, info){

    override fun getName(): String {
        return "MutualRelations:${notateKey()}"
    }

}

class SelfResourcesRelation(path : String, probability: Double = 1.0, info: String = "") : ResourceRelatedToResources(mutableListOf(path), mutableListOf(path), probability, info)