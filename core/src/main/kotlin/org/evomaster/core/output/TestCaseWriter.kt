package org.evomaster.core.output

import com.google.gson.Gson
import com.google.gson.internal.LinkedTreeMap
import org.apache.commons.lang3.StringEscapeUtils
import org.evomaster.core.EMConfig
import org.evomaster.core.Lazy
import org.evomaster.core.database.DbAction
import org.evomaster.core.output.formatter.OutputFormatter
import org.evomaster.core.problem.rest.RestCallAction
import org.evomaster.core.problem.rest.RestCallResult
import org.evomaster.core.problem.rest.RestIndividual
import org.evomaster.core.problem.rest.UsedObjects
import org.evomaster.core.problem.rest.param.BodyParam
import org.evomaster.core.problem.rest.param.HeaderParam
import org.evomaster.core.search.EvaluatedAction
import org.evomaster.core.search.gene.Gene
import org.evomaster.core.search.gene.SqlForeignKeyGene
import org.evomaster.core.search.gene.SqlPrimaryKeyGene
import javax.ws.rs.core.MediaType


class TestCaseWriter {

    private var counter = 0
    private var usedObjects = UsedObjects()
    //private var relevantObjects: List<Gene> = listOf()

    //TODO: refactor in constructor, and take out of convertToCompilableTestCode
    private var format: OutputFormat = OutputFormat.JAVA_JUNIT_4
    private lateinit var configuration: EMConfig


    fun convertToCompilableTestCode(
            //format: OutputFormat,
            config: EMConfig,
            test: TestCase,
            baseUrlOfSut: String)
            : Lines {

        //TODO: refactor remove once changes merged
        configuration = config
        this.format = config.outputFormat


        counter = 0

        val lines = Lines()

        if (format.isJUnit()) {
            lines.add("@Test")
        }

        when {
            format.isJava() -> lines.add("public void ${test.name}() throws Exception {")
            format.isKotlin() -> lines.add("fun ${test.name}()  {")
        }

        lines.indented {

            if (test.test.individual is RestIndividual) {
                // BMR: test.test should have the used objects attached (if any).
                //usedObjects = test.test.individual.usedObjects

                if (config.enableCompleteObjects) {
                    usedObjects = test.test.individual.usedObjects
                }


                if (!test.test.individual.dbInitialization.isEmpty()) {
                    handleDbInitialization(format, test.test.individual.dbInitialization, lines)
                }
            }


            if (test.hasChainedLocations()) {
                lines.addEmpty()

                test.test.evaluatedActions()
                        .map { it.action }
                        .filterIsInstance(RestCallAction::class.java)
                        .filter { it.locationId != null }
                        .map { it.locationId }
                        .distinct()
                        .forEach { id ->
                            val name = locationVar(id!!)
                            when {
                                format.isJava() -> lines.add("String $name = \"\";")
                                format.isKotlin() -> lines.add("var $name = \"\"")
                            }
                        }
            }


            test.test.evaluatedActions().forEach { a ->
                when (a.action) {
                    is RestCallAction -> handleRestCall(a, lines, baseUrlOfSut)
                    else -> throw IllegalStateException("Cannot handle " + a.action.getName())
                }
            }
        }
        lines.add("}")

        return lines
    }

    private fun handleDbInitialization(format: OutputFormat, dbInitialization: MutableList<DbAction>, lines: Lines) {


        dbInitialization.forEachIndexed { index, dbAction ->

            lines.add(when {
                index == 0 && format.isJava() -> "List<InsertionDto> insertions = sql()"
                index == 0 && format.isKotlin() -> "val insertions = sql()"
                else -> ".and()"
            } + ".insertInto(\"${dbAction.table.name}\", ${dbAction.geInsertionId()}L)")

            if (index == 0) {
                lines.indent()
            }

            lines.indented {
                dbAction.seeGenes().forEach { g ->

                    if (g.isPrintable()) {

                        when {
                            g is SqlForeignKeyGene -> {
                                val line = handleFK(g, dbAction)
                                lines.add(line)
                            }
                            g is SqlPrimaryKeyGene && g.gene is SqlForeignKeyGene -> {
                                /*
                                TODO: this will need to be refactored when Gene system
                                will have "previousGenes"-based methods on all genes
                             */
                                val line = handleFK(g.gene, dbAction)
                                lines.add(line)
                            }
                            else -> {
                                val variableName = g.getVariableName()
                                val printableValue = getPrintableValue(g)
                                lines.add(".d(\"$variableName\", \"$printableValue\")")
                            }
                        }
                    }
                }
            }

            if (index == dbInitialization.size - 1) {
                lines.add(".dtos()" +
                        when {
                            format.isJava() -> ";"
                            format.isKotlin() -> ""
                            else -> ""

                        })
            }

        }
        lines.deindent()

        var execInsertionsLine = "controller.execInsertionsIntoDatabase(insertions)"
        when {
            format.isJava() -> execInsertionsLine += ";"
            format.isKotlin() -> {
            }
        }
        lines.add(execInsertionsLine)
    }

    private fun getPrintableValue(g: Gene): String {
        if (g is SqlPrimaryKeyGene) {

            return getPrintableValue(g.gene)

        } else {
            return StringEscapeUtils.escapeJava(g.getValueAsPrintableString(targetFormat = format))
        }
    }

    private fun handleFK(g: SqlForeignKeyGene, action: DbAction): String {


        /*
            TODO: why the code here is not relying on SqlForeignKeyGene#getValueAsPrintableString ???
         */

        val variableName = g.getVariableName()
        /**
         * At this point all pk Ids should be valid
         * (despite they being NULL or not)
         **/
        Lazy.assert{g.hasValidUniqueIdOfPrimaryKey()}
        return if (g.isNull()) {
            ".d(\"$variableName\", \"NULL\")"
        } else {
            val keepAutoGeneratedValue = action.selectedColumns.filter { it.name == g.name }.first().foreignKeyToAutoIncrement
            val uniqueId = g.uniqueIdOfPrimaryKey //g.uniqueId
            if (keepAutoGeneratedValue) {
                ".r(\"$variableName\", ${uniqueId}L, true)"
            } else {
                ".r(\"$variableName\", ${uniqueId}L)"
            }
        }
    }


    private fun locationVar(id: String): String {
        //TODO make sure name is syntactically valid
        //TODO use counters to make them unique
        return "location_${id.trim().replace(" ", "_")}"
    }


    private fun handleRestCall(
            evaluatedAction: EvaluatedAction,
            lines: Lines,
            baseUrlOfSut: String
    ) {
        lines.addEmpty()

        val call = evaluatedAction.action as RestCallAction
        val res = evaluatedAction.result as RestCallResult

        if (res.failedCall()) {
            addRestCallInTryCatch(call, lines, res, baseUrlOfSut)
        } else {
            addRestCallLines(call, lines, res, baseUrlOfSut)
        }
    }

    private fun addRestCallInTryCatch(call: RestCallAction,
                                      lines: Lines,
                                      res: RestCallResult,
                                      baseUrlOfSut: String) {

        lines.add("try{")
        lines.indented {
            addRestCallLines(call, lines, res, baseUrlOfSut)

            if (!res.getTimedout()) {
                /*
                Fail test if exception is not thrown, but not if it was a timeout,
                otherwise the test would become flaky
              */
                lines.add("fail(\"Expected exception\");")
            }
        }

        lines.add("} catch(Exception e){")

        res.getErrorMessage()?.let {
            lines.indented {
                lines.add("//$it")
            }
        }
        lines.add("}")
    }

    private fun addRestCallLines(call: RestCallAction,
                                 lines: Lines,
                                 res: RestCallResult,
                                 baseUrlOfSut: String) {

        //first handle the first line
        handleFirstLine(call, lines, res)
        lines.indent(2)

        handleHeaders(call, lines)

        handleBody(call, lines)

        handleVerb(baseUrlOfSut, call, lines)

        handleResponse(lines, res)

        //finally, handle the last line(s)
        handleLastLine(call, res, lines)

        //BMR should expectations be here?
        // Having them at the end of a test makes some sense...
        if(configuration.expectationsActive){
            handleExpectations(res, lines, true)
        }


    }

    private fun handleLastLine(call: RestCallAction, res: RestCallResult, lines: Lines) {

        if (call.saveLocation && !res.stopping) {

            if (!res.getHeuristicsForChainedLocation()) {
                lines.add(".extract().header(\"location\");")
                lines.addEmpty()
                lines.deindent(2)
                lines.add("assertTrue(isValidURIorEmpty(${locationVar(call.path.lastElement())}));")
            } else {
                //TODO BMR: this is a less-than-subtle way to try to fix a problem in ScoutAPI
                // The test generated in java causes a fail due to .path<Object>
                val extraTypeInfo = when{
                    format.isKotlin() -> "<Object>"
                    else -> ""
                }
                lines.add(".extract().body().path$extraTypeInfo(\"${res.getResourceIdName()}\").toString();")
                lines.addEmpty()
                lines.deindent(2)

                val baseUri: String = if (call.locationId != null) {
                    locationVar(call.locationId!!)
                } else {
                    call.path.resolveOnlyPath(call.parameters)
                }

                lines.add("${locationVar(call.path.lastElement())} = \"$baseUri/\" + id_$counter;")
                counter++
            }
        } else {
            lines.append(";")
            lines.deindent(2)
        }
    }

    private fun handleFirstLine(call: RestCallAction, lines: Lines, res: RestCallResult) {
        lines.addEmpty()
        if (call.saveLocation && !res.stopping) {

            if (!res.getHeuristicsForChainedLocation()) {
                lines.append("${locationVar(call.path.lastElement())} = ")
            } else {
                if(format.isJava()) {
                    lines.append("String id_$counter = ")
                } else {
                    lines.append("val id_$counter: String = ")
                }
            }
        }
        lines.append("given()" + getAcceptHeader())
    }

    private fun handleVerb(baseUrlOfSut: String, call: RestCallAction, lines: Lines) {
        val verb = call.verb.name.toLowerCase()
        lines.add(".$verb(")
        if (call.locationId != null) {
            lines.append("resolveLocation(${locationVar(call.locationId!!)}, $baseUrlOfSut + \"${call.resolvedPath()}\")")

        } else {

            lines.append("$baseUrlOfSut + ")

            if (call.path.numberOfUsableQueryParams(call.parameters) <= 1) {
                val uri = call.path.resolve(call.parameters)
                lines.append("\"$uri\"")
            } else {
                //several query parameters. lets have them one per line
                val path = call.path.resolveOnlyPath(call.parameters)
                val elements = call.path.resolveOnlyQuery(call.parameters)

                lines.append("\"$path?\" + ")

                lines.indented {
                    (0 until elements.lastIndex).forEach { i -> lines.add("\"${elements[i]}&\" + ") }
                    lines.add("\"${elements.last()}\"")
                }
            }
        }
        lines.append(")")
    }

    private fun handleResponse(lines: Lines, res: RestCallResult) {
        if (!res.failedCall()) {
            lines.add(".then()")
            lines.add(".statusCode(${res.getStatusCode()})")

            if(configuration.enableBasicAssertions) {
                handleResponseContents(lines, res)
            }

            //TODO check on body
        }
    }

    private fun handleFieldValues(resContentsItem: Any?): String{
        if (resContentsItem == null) {
            return "nullValue()"
        }
        else{
            when(resContentsItem::class) {
                //Double::class -> return "anyOf(equalTo(${(Math.floor(resContentsItem as Double).toInt())}), closeTo(${(resContentsItem as Double)}, 0.1))"
                Double::class -> return "NumberMatcher.numberMatches(${resContentsItem as Double})"
                String::class -> return "containsString(\"${(resContentsItem as String).replace("\"", "\\\"").replace("\n", "\\n")}\")"
                //Note: checking a string can cause (has caused) problems due to unescaped quotation marks
                // The above solution should be refined.
                else -> return "NotCoveredYet"
            }
        }
        /* BMR: the code above is due to a somewhat unfortunate problem:
        - Gson does parses all numbers as Double
        - Hamcrest has a hard time comparing double to int
        This is (admittedly) a horrible hack, but it should address the issue until a more elegant solution can be found.
        Note: it also results in an odd behaviour in the generated test: IntelliJ will complain, but the test is executable.
        * */
    }

    private fun handleResponseContents(lines: Lines, res: RestCallResult) {
        lines.indented{
            lines.add(".assertThat()")

                if(res.getBodyType()==null) lines.add(".contentType(\"\")")
                else lines.add(".contentType(\"${res.getBodyType()
                        .toString().split(";").first() //TODO this is somewhat unpleasant. A more elegant solution is needed.
                }\")")

                /*if(res.getBodyType()!= null && res.getStatusCode()!=500){
                    lines.add(".contentType(\"${res.getBodyType()}\")")
                }

                if(res.getStatusCode() == 500){
                    val justACheck = res.getBodyType()
                }*/

                val bodyString = res.getBody()

                if(res.getBodyType()!= null){
                    val type = res.getBodyType()!!
                    if (type.isCompatible(MediaType.APPLICATION_JSON_TYPE)){
                        when (bodyString?.first()) {
                            '[' -> {
                                // This would be run if the JSON contains an array of objects.
                                // Only assertions on array size are supporte at the moment.
                                val resContents = Gson().fromJson(res.getBody(), ArrayList::class.java)
                                lines.add(".body(\"size()\", equalTo(${resContents.size}))")

                            }
                            '{' -> {
                                // JSON contains an object
                                val resContents = Gson().fromJson(res.getBody(), LinkedTreeMap::class.java)
                                resContents.keys.filter{
                                    !(it as String).contains("timestamp")}
                                        .forEach {
                                    val actualValue = resContents[it]
                                    if (actualValue != null) {
                                        val printableTh = handleFieldValues(actualValue)
                                        if (printableTh != "null"
                                                && printableTh != "NotCoveredYet"
                                                && !printableTh.contains("logged")
                                        ) {
                                            lines.add(".body(\"\'${it}\'\", ${printableTh})")
                                        }
                                    }
                                }
                            }
                            //'"' -> {
                                // This branch will be called if the JSON is a String
                                // Currently, it only supports very basic string matching
                             //   val resContents = Gson().fromJson(res.getBody(), String::class.java)
                             //   lines.add(".body(containsString(\"${resContents}\"))")
                            //}
                            else -> {
                                // This branch will be called if the JSON is null (or has a basic type)
                                // Currently, it converts the contents to String.
                                // TODO: if the contents are not a valid form of that type, expectations should be developed to handle the case
                                //val resContents = Gson().fromJson("\"" + res.getBody() + "\"", String::class.java)
                                //lines.add(".body(containsString(\"${resContents}\"))")
                            }
                        }
                    }
                }
        }
        //handleExpectations(res, lines, true)
    }

    private fun handleBody(call: RestCallAction, lines: Lines) {
        handleBody(call, lines, true)
    }

    private fun handleBody(call: RestCallAction, lines: Lines, readable: Boolean) {

        val bodyParam = call.parameters.find { p -> p is BodyParam }
        val form = call.getBodyFormData()

        if (bodyParam != null && form != null) {
            throw IllegalStateException("Issue: both Body and FormData present")
        }

        if(bodyParam != null && bodyParam is BodyParam) {

            lines.add(".contentType(\"${bodyParam.contentType()}\")")

            if(bodyParam.isJson()) {

                val body = if (readable) {
                    OutputFormatter.JSON_FORMATTER.getFormatted(bodyParam.gene.getValueAsPrintableString(mode = "json", targetFormat = format))
                } else {
                    bodyParam.gene.getValueAsPrintableString(mode = "json", targetFormat = format)
                }

                //needed as JSON uses ""
                val bodyLines = body.split("\n").map { s ->
                    "\"" + s.trim().replace("\"", "\\\"") + "\""
                }

                if (bodyLines.size == 1) {
                    lines.add(".body(${bodyLines.first()})")
                } else {
                    lines.add(".body(${bodyLines.first()} + ")
                    lines.indented {
                        (1 until bodyLines.lastIndex).forEach { i ->
                            lines.add("${bodyLines[i]} + ")
                        }
                        lines.add("${bodyLines.last()})")
                    }
                }

            } /* else if(bodyParam.isXml()) {
                val body = bodyParam.gene.getValueAsPrintableString("xml")
                lines.add(".body(\"$body\")")
            } */ else if(bodyParam.isTextPlain()) {
                val body = bodyParam.gene.getValueAsPrintableString(mode = "text", targetFormat = format)
                lines.add(".body($body)")
            } else {
                throw IllegalStateException("Unrecognized type: " + bodyParam.contentType())
            }
        }

        if (form != null) {
            lines.add(".contentType(\"application/x-www-form-urlencoded\")")
            lines.add(".body(\"$form\")")
        }
    }

    private fun handleHeaders(call: RestCallAction, lines: Lines) {

        val prechosenAuthHeaders = call.auth.headers.map { it.name }

        call.auth.headers.forEach {
            lines.add(".header(\"${it.name}\", \"${it.value}\") // ${call.auth.name}")
        }

        call.parameters.filterIsInstance<HeaderParam>()
                .filter { !prechosenAuthHeaders.contains(it.name) }
                .forEach {
                    lines.add(".header(\"${it.name}\", ${it.gene.getValueAsPrintableString(targetFormat = format)})")
                }
    }

    private fun getAcceptHeader(): String {
        /*
         *  Note: using the type in result body is wrong:
         *  if you request a JSON but make an error, you might
         *  get back a text/plain with an explanation
         *
         *  TODO: get the type from the REST call
         */
        return ".accept(\"*/*\")"
    }

    private fun handleExpectations(result: RestCallResult, lines: Lines, active: Boolean){

        /*
        TODO: This is a WiP to show the basic idea of the expectations:
        An exception is thrown ONLY if the expectations are set to active.
        If inactive, the condition will still be processed (with a goal to later adding to summaries or
        other information processing/handling that might be needed), but it does not cause
        the test case to fail regardless of truth value.

        The example below aims to show this behaviour and provide a reminder.
        As it is still work in progress, expect quite significant changes to this.
        */

        lines.add("expectationHandler()")
        lines.indented {
            lines.add(".expect()")
            //lines.add(".that(activeExpectations, true)")
            //lines.add(".that(activeExpectations, false)")
            if(configuration.enableCompleteObjects == false){
                addExpectationsWithoutObjects(result, lines)
            }
            lines.append(when {
                format.isJava() -> ";"
                else -> ""
            })
        }
    }

    private fun addExpectationsWithoutObjects(result: RestCallResult, lines: Lines){
        if(result.getBodyType() != null){
            // if there is a body, add expectations based on the body type. Right now only application/json is supported
            when(result.getBodyType().toString()){
                "application/json" -> {
                    when (result.getBody()?.first()) {
                        '[' -> {
                            // This would be run if the JSON contains an array of objects
                            val resContents = Gson().fromJson(result.getBody(), ArrayList::class.java)
                        }
                        '{' -> {
                            // This would be run if the JSON contains a single object
                            val resContents = Gson().fromJson(result.getBody(), Object::class.java)

                            (resContents as LinkedTreeMap<*, *>).keys.forEach {
                                val printableTh = handleFieldValues(resContents[it]!!)
                                if(printableTh != "null" && printableTh != "NotCoveredYet"){
                                    //lines.add(".body(\"${it}\", ${printableTh})")
                                    //lines.add(".that(activeExpectations, (\"${it}\".${printableTh}))")
                                    lines.add(".that(activeExpectations, (\"${it}\" == \"${resContents[it]}\"))")
                                }
                            }
                        }
                        else -> {
                            // this shouldn't be run if the JSON is okay. Panic! Update: could also be null. Pause, then panic!
                        }
                    }
                }
            }
        }
    }

    private fun addMetaDataComments(test: TestCase, lines: Lines){
        lines.add("/**")
        lines.add("* Targets this test covers   : " + test.test.fitness.coveredTargets())
        lines.add("* db initializations         : " + test.test.individual.seeInitializingActions().size)
        lines.add("*  number of actions         : " + test.test.individual.seeActions().size)
        lines.add("*/")
    }

}