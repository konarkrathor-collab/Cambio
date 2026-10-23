# Order Matching System with Currency Loophole Detector

> **Course:** Advanced Data Structures (ADS – `702IT0E009`)  
> **Institution:** SVKM's NMIMS MPSTME / STME  
> **Faculty In-Charge:** Prof. Ashwini Rao  
> **Group Members:** Krishna Solanki (`I075`), Konark Rathor (`I062`), Palak Savla (`F054`)

---

## 📌 Project Summary
A full-stack, high-throughput trading exchange and forex market scanner:
1. **Order Matching Engine:** Custom in-memory **Radix Trees** and **Hash-Indexed Doubly Linked Lists** for deterministic Price-Time Priority (FIFO) matching and $O(1)$ order cancellation.
2. **Currency Loophole Detector:** Directed Weighted Graph with $-\ln(R)$ transformation running the **Bellman-Ford Algorithm** to instantly detect profitable triangular arbitrage cycles.
3. **Auxiliary Systems:** **Hash Map + LRU Cache** for active trader margin caching, **Prefix Trie** for ticker autocomplete, and **Huffman Coding** for trade log compression.
4. **Interactive Web Interface:** Real-time visual order book depth chart, glowing force-directed forex graph, and a 5,000-order stress-test simulator.

---

## 📁 Repository Structure
```
order-matching-arbitrage-detector/
├── README.md                                  # This file
├── documentation/
│   ├── README.md                              # Documentation Index
│   ├── 00_group_and_course_details.md         # Member & Course Information
│   ├── 01_project_synopsis_and_proposal.md    # Proposal, Problem Statement & Motivation
│   ├── 02_system_architecture_and_design.md   # Architectural Specifications & API Contracts
│   ├── 03_data_structures_deep_dive.md        # Mathematical Formulations & Complexity Analysis
│   ├── 04_project_roadmap_and_milestones.md   # 14-Week Development Roadmap & Modules
│   ├── 05_data_structure_selection_rationale.md # Why: Comparative Survey, Now Benchmark-Verified
│   ├── 06_market_data_ingestion_service.md    # Real-Time Forex via Finnhub WebSocket + Fallback
│   ├── 07_team_workplan_and_phases.md         # Team Split, Backend Modules & Execution Phases
│   └── 08_build_plan_and_acceptance.md        # Stack, Stage 0-7 Build Plan & Acceptance Gates
└── benchmark/                                 # Benchmark lab: measured evidence behind doc 05
    ├── README.md                              # Pipeline, fairness rules, how to build & run
    ├── HANDOFF_DEEPSEEK.md                    # Original task brief for the benchmark modules
    ├── build.sh / run.sh                      # Plain-javac build + CLI runner (no build tool)
    ├── data/                                  # Archived real Frankfurter rate snapshot
    ├── results/                               # RESULTS.md (narrative, Runs 1–13) + raw_results.csv
    └── src/ads/bench/                         # core harness + ladder, queue, cache, arbitrage,
                                               #   ticker, compression modules
```

👉 Explore the full documentation in the **[documentation/](./documentation/README.md)** folder, and the measured evidence in **[benchmark/](./benchmark/README.md)**.
