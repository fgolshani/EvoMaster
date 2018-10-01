package org.evomaster.experiments.linear

import org.evomaster.core.search.Action
import org.evomaster.core.search.Individual
import org.evomaster.core.search.gene.DisruptiveGene
import org.evomaster.core.search.gene.Gene
import org.evomaster.core.search.gene.IntegerGene
import org.evomaster.core.search.service.Randomness


class LinearIndividual(val id: DisruptiveGene<IntegerGene>, val k: IntegerGene) : Individual() {

    override fun copy(): Individual {
        return LinearIndividual(id.copy() as DisruptiveGene<IntegerGene>, k.copy() as IntegerGene)
    }

    override fun seeGenes(filter: GeneFilter): List<out Gene> {
        return listOf(id, k)
    }

    override fun size(): Int {
        return 1
    }

    override fun seeActions(): List<out Action> {
        return listOf()
    }


    override fun verifyInitializationActions(): Boolean {
        return true
    }

    override fun repairInitializationActions(randomness: Randomness) {

    }
}