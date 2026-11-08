package com.mscient.intrapaper.g0;

import java.util.List;

/** Normative V1 vocabulary; no defaults, credentials, or executable access configuration. */
public final class G0Schema {
    private G0Schema() {}
    public static final String VERSION = "g0-v1";
    public static final List<String> CHARTER = List.of("charter_id", "classification", "assessment_date",
        "boundary", "order_route", "account_access", "network_access", "spending_authorized",
        "live_authorized", "data_uses", "ruleset_id", "limitations_acknowledged");
    public static final List<String> DECISIONS = List.of("operator", "stop_authority", "resume_authority",
        "human_reviewer", "incident_contact", "own_capital_only", "account_route_applicability",
        "risk_capital_definition", "risk_capital_value", "annual_spend_ceiling", "live_loss_ceiling",
        "risk_capital_is_loss_ceiling", "currency", "budget_period", "limits_are_actual",
        "strategy_scope", "order_activity_scope", "instruments_in", "instruments_out", "initial_universe",
        "access_path", "data_sources", "data_purposes", "data_storage", "data_retention", "data_sharing",
        "data_publication", "data_deletion", "stop_triggers", "escalation_authority", "recovery_requirements",
        "resume_conditions", "independent_review_completed", "ruleset_acceptance", "source_freshness_policy",
        "no_order_acknowledgement", "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10",
        "A11", "A12", "A13", "A14", "A15");
    public static final List<String> RULES = List.of("applicability", "implementation_dates", "responsibilities",
        "tagging", "static_ip", "api_keys", "authentication", "order_message_rates", "kill_switch",
        "audit_retention", "broker_endpoints", "broker_market_fields", "broker_rate_limits", "data_rights",
        "boundary_limitations");
    public static final String SOURCE_HEADER = "id\trule\truleset\tstatus\tauthority\tapplicability\ttitle\tpublisher\turl\tpublished\teffective\treviewed\tvalid_until\tsection\trequirement\tdata_source\tevidence\tsha256\treason";
    public static final String OWNER_HEADER = "id\tstatus\tvalue\treason\tevidence_kind\tdecided\tvalid_until";
    public static final String DATA_HEADER = "id\tsource\tstatus\tpurpose\tinstruments\tstorage\tretention_days\tsharing\tpublication\tdeletion\tallowed_purpose\tallowed_instruments\tallowed_storage\tmax_retention_days\tallowed_sharing\tallowed_publication\tallowed_deletion\trights_source\treason";
    public static final List<String> REVIEW = List.of("decision", "reviewer", "qualified", "qualification",
        "independent", "evidence_kind", "authentication", "reviewed", "valid_until", "pack_sha256", "evidence", "sha256", "reason");
}
