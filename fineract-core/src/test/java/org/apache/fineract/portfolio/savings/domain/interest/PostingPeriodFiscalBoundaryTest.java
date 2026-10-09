/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.savings.domain.interest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Compounding period boundaries must be derived from the year of the period itself, not from the business date, and
 * must stay on the last day of February in leap years.
 *
 * With principal 1000, a 36.5% annual rate and a 365 day year, every day earns exactly 1.00 on the uncompounded
 * principal, so compounding one day too early shows up as 366.37 instead of 366.00.
 */
class PostingPeriodFiscalBoundaryTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);
    private static final int MARCH_FISCAL_START = 3;

    private MockedStatic<MoneyHelper> moneyHelper;

    @BeforeEach
    void setUp() {
        moneyHelper = Mockito.mockStatic(MoneyHelper.class);
        moneyHelper.when(MoneyHelper::getMathContext).thenReturn(new MathContext(12, RoundingMode.HALF_EVEN));
        moneyHelper.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
    }

    @AfterEach
    void tearDown() {
        moneyHelper.close();
        ThreadLocalContextUtil.reset();
    }

    private static void setBusinessDate(LocalDate date) {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, date)));
    }

    private static PostingPeriod postingPeriod(LocalDate start, LocalDate end, SavingsCompoundingInterestPeriodType compounding,
            int fiscalStartMonth) {
        return PostingPeriod.createFrom(LocalDateInterval.create(start, end), Money.of(CURRENCY, BigDecimal.valueOf(1000)),
                Collections.emptyList(), CURRENCY, compounding, SavingsInterestCalculationType.DAILY_BALANCE, new BigDecimal("0.365"), 365L,
                end, Collections.emptyList(), false, Money.zero(CURRENCY), false, false, fiscalStartMonth);
    }

    private static BigDecimal interest(PostingPeriod period) {
        return period.calculateInterest(new CompoundInterestValues(BigDecimal.ZERO, BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);
    }

    @Test
    void annualCompounding_marchFiscalStart_leapYearEndsOnFeb29() {
        setBusinessDate(LocalDate.of(2024, 6, 15));

        PostingPeriod period = postingPeriod(LocalDate.of(2023, 3, 1), LocalDate.of(2024, 2, 29),
                SavingsCompoundingInterestPeriodType.ANNUAL, MARCH_FISCAL_START);

        // 366 days at 1.00/day, compounded only at the very end of the period
        assertThat(interest(period)).isEqualByComparingTo("366.00");
    }

    @Test
    void annualCompounding_marchFiscalStart_nonLeapYearEndsOnFeb28() {
        setBusinessDate(LocalDate.of(2024, 6, 15));

        PostingPeriod period = postingPeriod(LocalDate.of(2024, 3, 1), LocalDate.of(2025, 2, 28),
                SavingsCompoundingInterestPeriodType.ANNUAL, MARCH_FISCAL_START);

        // 365 days at 1.00/day
        assertThat(interest(period)).isEqualByComparingTo("365.00");
    }

    @Test
    void annualCompounding_defaultFiscalStart_isUnchanged() {
        setBusinessDate(LocalDate.of(2024, 6, 15));

        PostingPeriod period = postingPeriod(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31),
                SavingsCompoundingInterestPeriodType.ANNUAL, 1);

        // 366 days at 1.00/day
        assertThat(interest(period)).isEqualByComparingTo("366.00");
    }

    @Test
    void quarterlyCompounding_februaryInNonLeapYear_businessDateInLeapYear_doesNotThrow() {
        setBusinessDate(LocalDate.of(2024, 6, 15));

        assertThatCode(() -> postingPeriod(LocalDate.of(2025, 2, 28), LocalDate.of(2025, 2, 28),
                SavingsCompoundingInterestPeriodType.QUATERLY, MARCH_FISCAL_START)).doesNotThrowAnyException();
    }

    @Test
    void biAnnualCompounding_februaryInNonLeapYear_businessDateInLeapYear_doesNotThrow() {
        setBusinessDate(LocalDate.of(2024, 6, 15));

        assertThatCode(() -> postingPeriod(LocalDate.of(2025, 2, 28), LocalDate.of(2025, 2, 28),
                SavingsCompoundingInterestPeriodType.BI_ANNUAL, MARCH_FISCAL_START)).doesNotThrowAnyException();
    }

    @Test
    void quarterlyCompounding_rolloverIntoLeapFebruary_compoundsOnFeb29() {
        // business date in a non-leap year must not shorten February of the (leap) target year
        setBusinessDate(LocalDate.of(2025, 6, 15));

        // March fiscal start: quarters end Feb, May, Aug, Nov. A period starting 2023-12-01 rolls into Feb 2024.
        PostingPeriod period = postingPeriod(LocalDate.of(2023, 12, 1), LocalDate.of(2024, 2, 29),
                SavingsCompoundingInterestPeriodType.QUATERLY, MARCH_FISCAL_START);

        // 91 days (31 + 31 + 29) at 1.00/day, compounded only at the end of the quarter
        assertThat(interest(period)).isEqualByComparingTo("91.00");
    }
}
