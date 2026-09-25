import asyncio
import time
import httpx
import requests

TARGET_URL = "http://127.0.0.1:8085/"
TOTAL_REQUESTS = 100

async def heartbeat(stop_event, log_holder):
    start = time.perf_counter()
    ticks = 0
    while not stop_event.is_set():
        ticks += 1
        now = time.perf_counter() - start
        log_holder.append(f"Heartbeat tick #{ticks} at +{now:.3f}s")
        try:
            await asyncio.sleep(0.05)  # 50 ms
        except asyncio.CancelledError:
            break

async def run_async_baseline():
    print("\nRunning 1. Python Async Baseline (httpx.AsyncClient + asyncio.gather)...")
    stop_event = asyncio.Event()
    heartbeat_logs = []
    hb_task = asyncio.create_task(heartbeat(stop_event, heartbeat_logs))

    start = time.perf_counter()
    # Configure client with sufficient connection limits for 100 concurrent requests
    limits = httpx.Limits(max_connections=120, max_keepalive_connections=100)
    async with httpx.AsyncClient(limits=limits, timeout=10.0) as client:
        tasks = [client.get(TARGET_URL) for _ in range(TOTAL_REQUESTS)]
        responses = await asyncio.gather(*tasks)
        for r in responses:
            assert r.status_code == 200

    elapsed = time.perf_counter() - start
    stop_event.set()
    hb_task.cancel()
    try:
        await hb_task
    except asyncio.CancelledError:
        pass

    print(f"  Completed in: {elapsed * 1000:.1f} ms ({elapsed:.2f} s)")
    print(f"  Heartbeat was able to tick {len(heartbeat_logs)} times during execution.")
    return elapsed * 1000

async def run_sync_sabotage():
    print("\nRunning 2. Python Sabotage (requests.get inside coroutines + 50ms heartbeat)...")
    stop_event = asyncio.Event()
    heartbeat_logs = []
    hb_task = asyncio.create_task(heartbeat(stop_event, heartbeat_logs))

    async def blocking_task(session):
        # Sabotage: Synchronous blocking call inside coroutine without yielding!
        r = session.get(TARGET_URL, timeout=10)
        assert r.status_code == 200

    start = time.perf_counter()
    with requests.Session() as session:
        tasks = [blocking_task(session) for _ in range(TOTAL_REQUESTS)]
        await asyncio.gather(*tasks)

    elapsed = time.perf_counter() - start
    stop_event.set()
    hb_task.cancel()
    try:
        await hb_task
    except asyncio.CancelledError:
        pass

    print(f"  Completed in: {elapsed * 1000:.1f} ms ({elapsed:.2f} s)")
    print(f"  Heartbeat was able to tick {len(heartbeat_logs)} times during execution!")
    if len(heartbeat_logs) <= 1:
        print("  -> OBSERVATION: The heartbeat was completely STARVED and FROZEN by synchronous calls!")
    return elapsed * 1000

async def main():
    print("==================================================")
    print("Python 3.11+ Concurrency Benchmark (100 reqs @ 200ms delay)")
    print("==================================================")

    t_async = await run_async_baseline()
    t_sabotage = await run_sync_sabotage()

    print("\n--- Python Summary ---")
    print(f"Async Baseline (httpx):     {t_async:.1f} ms ({t_async/1000:.2f} s)")
    print(f"Sabotage (requests.get):    {t_sabotage:.1f} ms ({t_sabotage/1000:.2f} s)")

if __name__ == '__main__':
    asyncio.run(main())
