const TARGET_URL = "http://127.0.0.1:8085/";
const TOTAL_REQUESTS = 100;

function burnCpu(ms) {
    const end = performance.now() + ms;
    while (performance.now() < end) {
        // Synchronous spin loop: monopolizes the V8 thread & freezes the event loop
    }
}

async function runBaseline() {
    console.log("\nRunning 1. Node.js Baseline (Promise.all over fetch)...");
    const start = performance.now();

    const promises = Array.from({ length: TOTAL_REQUESTS }, async () => {
        const res = await fetch(TARGET_URL);
        if (!res.ok) {
            throw new Error(`HTTP ${res.status}`);
        }
        return res.json();
    });

    await Promise.all(promises);
    const elapsed = performance.now() - start;
    console.log(`  Completed in: ${elapsed.toFixed(1)} ms (${(elapsed / 1000).toFixed(2)} s)`);
    return elapsed;
}

async function runSabotage() {
    console.log("\nRunning 2. Node.js Sabotage (200ms synchronous CPU loop before each call)...");
    const start = performance.now();

    const promises = Array.from({ length: TOTAL_REQUESTS }, async () => {
        burnCpu(200); // Sabotage: 200 ms synchronous busy-wait before fetching
        const res = await fetch(TARGET_URL);
        if (!res.ok) {
            throw new Error(`HTTP ${res.status}`);
        }
        return res.json();
    });

    await Promise.all(promises);
    const elapsed = performance.now() - start;
    console.log(`  Completed in: ${elapsed.toFixed(1)} ms (${(elapsed / 1000).toFixed(2)} s)`);
    return elapsed;
}

async function main() {
    console.log("==================================================");
    console.log("Node.js 20+ Concurrency Benchmark (100 reqs @ 200ms delay)");
    console.log("==================================================");

    const tBaseline = await runBaseline();
    const tSabotage = await runSabotage();

    console.log("\n--- Node.js Summary ---");
    console.log(`Baseline (Promise.all + fetch): ${tBaseline.toFixed(1)} ms (${(tBaseline / 1000).toFixed(2)} s)`);
    console.log(`Sabotage (200ms CPU loop):      ${tSabotage.toFixed(1)} ms (${(tSabotage / 1000).toFixed(2)} s)`);
}

main();
