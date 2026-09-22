package org.example

import org.example.api.model.PaymentRequestDto
import org.example.model.PaymentModel

fun PaymentRequestDto.toModel(): PaymentModel = PaymentModel.from(this)
