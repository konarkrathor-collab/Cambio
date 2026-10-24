# Project Documentation Index

Technical documentation for the **Advanced Data Structures (ADS – 702IT0E009)** course mini project. For the quick start, the gates and the headline results, start at the [repository README](../README.md).

---

## Project Overview
* **Title:** Order Matching System with Currency Loophole Detector
* **Domain:** High-Throughput Financial Systems, Graph Optimization & Forex Arbitrage
* **Institution:** SVKM's NMIMS MPSTME / STME
* **Faculty In-Charge:** Prof. Ashwini Rao
* **Release:** v1.1.0

---

## Documentation Directory

| Document | Description | Key Topics |
| :--- | :--- | :--- |
| **[00_group_and_course_details.md](./00_group_and_course_details.md)** | Group & Course Metadata | Members, roll numbers, programme, faculty in-charge |
| **[01_project_synopsis_and_proposal.md](./01_project_synopsis_and_proposal.md)** | Synopsis & Proposal (as built) | Summary, stack as built, problem, objectives with status, syllabus mapping, what changed from the proposal |
| **[02_system_architecture_and_design.md](./02_system_architecture_and_design.md)** | System Architecture (as built) | Java backend + Python feed + React dashboard, threads, subsystems, order and rate data flows, SSE, persistence and crash safety |
| **[03_data_structures_deep_dive.md](./03_data_structures_deep_dive.md)** | Data Structures Deep Dive | B+ tree ladder, DLL + engine-wide hash index, Bellman-Ford on $-\ln(R)$ with exact triangles, hash + LRU, trie, Huffman; complexity; production versus benchmark |
| **[04_project_roadmap_and_milestones.md](./04_project_roadmap_and_milestones.md)** | Roadmap & Targets | The original 14-week plan with as-built notes; the doc 04 performance targets with their measured values, met or missed |
| **[05_data_structure_selection_rationale.md](./05_data_structure_selection_rationale.md)** | Selection Rationale (Why) | Rubric scoring, rejected alternatives, benchmark evidence per subsystem, the ladder decision, production versus benchmark |
| **[06_market_data_ingestion_service.md](./06_market_data_ingestion_service.md)** | Market Data Ingestion (as built) | Finnhub WebSocket feed, Frankfurter fallback, the INR finding, quote validity, the threshold study, failure handling |
| **[07_team_workplan_and_phases.md](./07_team_workplan_and_phases.md)** | What Was Built & Slice Ownership | Structures and modules as built, the three vertical slices (ownership for the team to confirm), phases mapped to build stages |
| **[08_build_plan_and_acceptance.md](./08_build_plan_and_acceptance.md)** | Build Plan & Acceptance Gates | Stack, repository rules, the ladder decision, Stage 0–7 plan with gates, matching rules, feed payloads |
| **[09_api_contract.md](./09_api_contract.md)** | API Contract | Every endpoint, field, error code and SSE event, with captured requests and responses, and the change log |
| **[10_final_report.md](./10_final_report.md)** | Final Project Report | Abstract, problem, design, structure selection and evidence, implementation, evaluation, bugs found and fixed, limitations, conclusion |
| **[DEMO.md](./DEMO.md)** | Demo Script | A 10–12 minute live demo, step by step, with "why this structure" answers and likely faculty questions |
| **[results/](./results/README.md)** | Committed Measurements | The final run's raw output: benchmark lab, parity, scan timing, simulator, threshold study, verify-all, drills, soak |

Further records outside this folder:
* [benchmark/results/RESULTS.md](../benchmark/results/RESULTS.md): the benchmark lab's evidence log, Runs 1–15.
* [backend/BUGS.md](../backend/BUGS.md): every bug found during the build, with what broke, why, and the fix.
* The READMEs of [backend](../backend/README.md), [feed](../feed/README.md), [frontend](../frontend/README.md) and [scripts](../scripts/README.md).

---

## Group Members

* **Krishna Solanki** (`I075`) – MBA Tech (IT), Sem V, Year 3
* **Konark Rathor** (`I062`) – MBA Tech (IT), Sem V, Year 3
* **Palak Savla** (`F054`) – B Tech Integration (IT), Sem IX, Year 5
