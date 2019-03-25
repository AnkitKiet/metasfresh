package de.metas.paypalplus.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;

import java.time.LocalDate;

/*
 * #%L
 * de.metas.paypalplus.model
 * %%
 * Copyright (C) 2019 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
@RequiredArgsConstructor
@Value
@Getter
@Builder
public class PayPalPlusPayment
{
	@NonNull
	String id;

	@NonNull
	String paymentDocumentNumber;

	@NonNull
	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
	LocalDate paymentDate;

	@NonNull
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	String paymentAmount;

	@NonNull
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	String paymentCurrency;
}
