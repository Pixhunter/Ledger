package org.example.api

import org.example.api.generated.model.ErrorReasonDto
import org.example.api.generated.model.PaymentResponseDto
import org.example.api.generated.model.StatusDto

fun paymentRecorded() = PaymentResponseDto(StatusDto.SUCCESS)

fun paymentRejected(reason: ErrorReasonDto) = PaymentResponseDto(StatusDto.FAILED, reason)
