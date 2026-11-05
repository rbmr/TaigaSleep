package com.rbmr.taigasleep.guard

import com.rbmr.taigasleep.R

// The required step on every flow: one tap opens the system popup that grants the exemption.
private val exemptionHelper = Helper(R.string.g_helper_exemption, GuidanceAction.REQUEST_EXEMPTION)

private val chargeStep =
    Step(
        kind = StepKind.RECOMMENDED,
        id = "charge",
        titleRes = R.string.g_charge_title,
        descriptionRes = R.string.g_charge_description,
        guideRes = listOf(R.string.g_charge_g1),
    )

val OnePlusFlow =
    Flow(
        id = "oneplus",
        nameRes = R.string.g_flow_oneplus,
        steps =
            listOf(
                Step(
                    kind = StepKind.REQUIRED,
                    id = "background",
                    titleRes = R.string.g_oneplus_background_title,
                    descriptionRes = R.string.g_oneplus_background_description,
                    helper = exemptionHelper,
                    check = { it.backgroundAllowed },
                ),
                Step(
                    kind = StepKind.RECOMMENDED,
                    id = "sleep_standby",
                    titleRes = R.string.g_oneplus_sleep_title,
                    descriptionRes = R.string.g_oneplus_sleep_description,
                    guideRes = listOf(R.string.g_oneplus_sleep_g1, R.string.g_oneplus_sleep_g2),
                ),
                Step(
                    kind = StepKind.RECOMMENDED,
                    id = "recents",
                    titleRes = R.string.g_recents_title,
                    descriptionRes = R.string.g_oneplus_recents_description,
                    guideRes = listOf(R.string.g_oneplus_recents_g1, R.string.g_oneplus_recents_g2),
                ),
                chargeStep,
            ),
    )

val SamsungFlow =
    Flow(
        id = "samsung",
        nameRes = R.string.g_flow_samsung,
        steps =
            listOf(
                Step(
                    kind = StepKind.REQUIRED,
                    id = "unrestricted",
                    titleRes = R.string.g_samsung_unrestricted_title,
                    descriptionRes = R.string.g_samsung_unrestricted_description,
                    helper = exemptionHelper,
                    check = { it.backgroundAllowed },
                ),
                Step(
                    kind = StepKind.RECOMMENDED,
                    id = "sleeping_apps",
                    titleRes = R.string.g_samsung_sleeping_title,
                    descriptionRes = R.string.g_samsung_sleeping_description,
                    guideRes =
                        listOf(
                            R.string.g_samsung_sleeping_g1,
                            R.string.g_samsung_sleeping_g2,
                            R.string.g_samsung_sleeping_g3,
                        ),
                ),
                Step(
                    kind = StepKind.RECOMMENDED,
                    id = "keep_open",
                    titleRes = R.string.g_samsung_keep_open_title,
                    descriptionRes = R.string.g_samsung_keep_open_description,
                    guideRes =
                        listOf(R.string.g_samsung_keep_open_g1, R.string.g_samsung_keep_open_g2),
                ),
                chargeStep,
            ),
    )

val GenericFlow =
    Flow(
        id = "generic",
        nameRes = R.string.g_flow_generic,
        steps =
            listOf(
                Step(
                    kind = StepKind.REQUIRED,
                    id = "exemption",
                    titleRes = R.string.g_generic_exemption_title,
                    descriptionRes = R.string.g_generic_exemption_description,
                    helper = exemptionHelper,
                    check = { it.batteryExempted },
                ),
                Step(
                    kind = StepKind.RECOMMENDED,
                    id = "recents",
                    titleRes = R.string.g_recents_title,
                    descriptionRes = R.string.g_generic_recents_description,
                    guideRes = listOf(R.string.g_generic_recents_g1, R.string.g_generic_recents_g2),
                ),
                chargeStep,
            ),
    )
