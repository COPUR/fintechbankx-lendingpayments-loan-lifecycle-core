#!/usr/bin/env python3
"""Check rendered Helm manifests (stdin): the Flyway migration Job pod and the service selectors.

Mesh NetworkPolicies grant Aurora egress by app.kubernetes.io/name only, so the
migration Job pod must carry app.kubernetes.io/name=<service account> (and
app.kubernetes.io/component=db-migration). The Service, PodDisruptionBudget and
Deployment must then tell the two apart by app.kubernetes.io/component=api, so
none of them selects the Job pod.

usage: helm template ... | python3 scripts/ci/migration-pod-selection.py <service account>
Exits 1 with one line per violation.
"""
import sys

import yaml


def selector_of(doc):
    kind, spec = doc.get("kind"), doc.get("spec") or {}
    if kind == "Service":
        return spec.get("selector")
    if kind in ("Deployment", "PodDisruptionBudget", "NetworkPolicy"):
        key = "podSelector" if kind == "NetworkPolicy" else "selector"
        return (spec.get(key) or {}).get("matchLabels")
    return None


def main() -> int:
    service_account = sys.argv[1]
    docs = [d for d in yaml.safe_load_all(sys.stdin) if isinstance(d, dict)]
    problems = []
    jobs = [d for d in docs if d.get("kind") == "Job"]
    if len(jobs) != 1:
        problems.append(f"expected one migration Job, rendered {len(jobs)}")
    job_pod = {}
    for job in jobs:
        job_pod = ((job.get("spec") or {}).get("template") or {}).get("metadata", {}).get("labels") or {}
        if job_pod.get("app.kubernetes.io/name") != service_account:
            problems.append(f"Job pod app.kubernetes.io/name is {job_pod.get('app.kubernetes.io/name')!r},"
                            f" must be the service account {service_account!r} (mesh Aurora egress)")
        if job_pod.get("app.kubernetes.io/component") != "db-migration":
            problems.append("Job pod app.kubernetes.io/component must be db-migration")
    for doc in docs:
        selector = selector_of(doc)
        if selector is None:
            continue
        name = f"{doc['kind']} {doc.get('metadata', {}).get('name')}"
        if selector.get("app.kubernetes.io/component") != "api":
            problems.append(f"{name}: selector lacks app.kubernetes.io/component=api")
        if job_pod and all(job_pod.get(k) == v for k, v in selector.items()):
            problems.append(f"{name}: selector {selector} selects the migration Job pod")
    for problem in problems:
        print(problem, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
