package org.evomaster.core.problem.rest

/**
 * Specify how a REST individual was sampled.
 * This info is needed to have custom mutations of the
 * chromosome structures
 *
 * [SMART_RESOURCE_WITHOUT_DEP] is used to refer resource-based sampling
 */
enum class SampleType {
    RANDOM,
    SMART,
    SMART_RESOURCE_WITHOUT_DEP,
    SMART_RESOURCE_WITH_DEP,
    SMART_GET_COLLECTION
}