package eip.authz

import future.keywords.if

default allow := false

allow if {
    input.user.role == "CEO"
    input.resource.domain in {"it_domain", "finance_domain"}
}

allow if {
    input.user.role == "CEO"
    input.resource.path == "/api/sparql"
}

deny if {
    input.resource.path == "/$/server"
}
