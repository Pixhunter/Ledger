package org.example.statemachine

/**
 * Retryable vs permanent is the only distinction the runner needs: one costs
 * an attempt and comes back, the other fails the task immediately instead of
 * burning five attempts on something that will never succeed.
 */
sealed class StepError(message: String, cause: Throwable? = null) : Exception(message, cause)

class Retryable(message: String, cause: Throwable? = null) : StepError(message, cause)

class Permanent(message: String, cause: Throwable? = null) : StepError(message, cause)
