package org.evomaster.core.problem.rest.resource.util

import org.evomaster.client.java.controller.api.dto.database.operations.DataRowDto

class DbUtil {
    companion object {
        /*
        two purposes of the comparision:
        1) at the starting, check if data can be modified (if the rest follows the semantic, it should be added) by POST action of resources.
            based on the results, relationship between resource and table can be built.
        2) with the search, the relationship (resource -> table) can be further
     */
//        fun compareDB(call : ResourceRestCalls){
//
//            if(hasDBHandler()){
//                assert(call.doesCompareDB)
//
//                if((sampler as ResourceRestSampler).sqlInsertBuilder != null){
//
//                    val previous = dataInDB.toMutableMap()
//                    snapshotDB()
//
//                    /*
//                        using PKs, check whether any row is changed
//                        TODO further check whether any value of row is changed, e.g., pk keeps same, but one value of other columns is changed (PATCH)
//                     */
//                    val ar = call.resource.ar
//                    val tables = resourceTables.getOrPut(ar.path.toString()){ mutableSetOf() }
//                    if(isDBChanged(previous, dataInDB)){
//                        tables.addAll(tableChanged(previous, dataInDB))
//                        tables.addAll(tableChanged(dataInDB, previous))
//                    }
//                    if(call.dbActions.isNotEmpty()){
//                        tables.addAll(call.dbActions.map { it.table.name }.toHashSet())
//                    }
//                    updateDependency()
//                }
//            }
//        }

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

        fun formatTableName(tableName: String) : String = tableName.toLowerCase()

    }
}