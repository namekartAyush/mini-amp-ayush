const fs = require('fs');
const path = require('path');
const http = require('http');
const { Kafka } = require('kafkajs');

// Load environment variables
require('dotenv').config();

function readSecret(envVar, fileVar, defaultVal = '') {
  if (process.env[fileVar] && fs.existsSync(process.env[fileVar])) {
    return fs.readFileSync(process.env[fileVar], 'utf8').trim();
  }
  return process.env[envVar] || defaultVal;
}

function loadAndValidateConfig() {
  const nodeEnv = process.env.NODE_ENV || 'development';
  const port = parseInt(process.env.PORT || '3001', 10);
  const kafkaBrokers = (process.env.KAFKA_BROKERS || 'localhost:9092').split(',').map(b => b.trim());
  const kafkaGroupId = process.env.KAFKA_GROUP_ID || 'notifier-group';
  const kafkaTopic = process.env.KAFKA_TOPIC_AUCTION_EVENTS || 'auction.events';

  return {
    nodeEnv,
    port,
    kafka: { brokers: kafkaBrokers, groupId: kafkaGroupId, topic: kafkaTopic }
  };
}

const config = loadAndValidateConfig();
console.log('Starting Notifier Service with config:', JSON.stringify(config, null, 2));

// ==============================================================================
// Persistent Idempotent Store
// ==============================================================================
const STORE_PATH = path.join(__dirname, '..', 'notifications_store.json');
let processedEventIds = new Set();
let notifications = [];

function loadStore() {
  try {
    if (fs.existsSync(STORE_PATH)) {
      const raw = fs.readFileSync(STORE_PATH, 'utf8');
      const data = JSON.parse(raw);
      if (Array.isArray(data.processedEventIds)) {
        processedEventIds = new Set(data.processedEventIds);
      }
      if (Array.isArray(data.notifications)) {
        notifications = data.notifications;
      }
      console.log(`[Store] Loaded ${processedEventIds.size} idempotent event IDs and ${notifications.length} notifications from disk.`);
    }
  } catch (err) {
    console.warn('[Store] Failed to load store from disk, starting empty:', err.message);
  }
}

function saveStore() {
  try {
    const data = {
      processedEventIds: Array.from(processedEventIds),
      notifications: notifications
    };
    fs.writeFileSync(STORE_PATH, JSON.stringify(data, null, 2), 'utf8');
  } catch (err) {
    console.error('[Store] Failed to persist notifications to disk:', err.message);
  }
}

loadStore();

// ==============================================================================
// Server-Sent Events (SSE) Hub
// ==============================================================================
const sseClients = new Set();

function broadcastNotification(notification, isDuplicate = false) {
  if (isDuplicate) {
    console.log(`[SSE] Dropping broadcast for duplicate event ID: ${notification.eventId}`);
    return;
  }

  const payload = `event: notification\ndata: ${JSON.stringify(notification)}\n\n`;
  for (const client of sseClients) {
    try {
      client.write(payload);
    } catch (err) {
      console.warn('[SSE] Failed to write to client, removing:', err.message);
      sseClients.delete(client);
    }
  }
  console.log(`[SSE] Broadcast event ${notification.eventId} (${notification.eventType}) to ${sseClients.size} clients.`);
}

/**
 * Ingests an event idempotently.
 * Returns { success: boolean, duplicate: boolean, notification }
 */
function processEventIdempotently(eventData) {
  const eventId = eventData.eventId || eventData.id || `evt-${Date.now()}-${Math.random().toString(36).substr(2, 6)}`;
  
  if (processedEventIds.has(eventId)) {
    console.log(`[Idempotency] Duplicate event detected and ignored: ${eventId}`);
    return { success: true, duplicate: true, eventId };
  }

  const notification = {
    eventId: eventId,
    eventType: eventData.eventType || 'BID_PLACED',
    auctionId: String(eventData.auctionId || ''),
    bidderId: String(eventData.bidderId || ''),
    amount: parseFloat(eventData.amount || 0),
    timestamp: eventData.timestamp || new Date().toISOString()
  };

  processedEventIds.add(eventId);
  notifications.unshift(notification); // newest first
  saveStore();

  broadcastNotification(notification, false);
  return { success: true, duplicate: false, notification };
}

// ==============================================================================
// Kafka Consumer Setup
// ==============================================================================
let isKafkaConnected = false;
const kafka = new Kafka({
  clientId: 'notifier-service',
  brokers: config.kafka.brokers,
  retry: {
    initialRetryTime: 300,
    retries: 10
  }
});

const consumer = kafka.consumer({ groupId: config.kafka.groupId });

async function startKafkaConsumer() {
  try {
    console.log(`[Kafka] Connecting to brokers: ${config.kafka.brokers.join(', ')}...`);
    await consumer.connect();
    isKafkaConnected = true;
    console.log(`[Kafka] Connected. Subscribing to topic: ${config.kafka.topic} (group: ${config.kafka.groupId})`);
    
    await consumer.subscribe({ topic: config.kafka.topic, fromBeginning: true });
    
    await consumer.run({
      eachMessage: async ({ topic, partition, message }) => {
        const rawValue = message.value ? message.value.toString() : '';
        const offset = message.offset;
        console.log(`[Kafka] Received message on ${topic}[${partition}] offset=${offset}`);
        
        try {
          const parsed = JSON.parse(rawValue);
          processEventIdempotently(parsed);
        } catch (parseErr) {
          console.error(`[Kafka] Non-JSON message skipped at offset ${offset}:`, rawValue);
        }
      }
    });
  } catch (err) {
    isKafkaConnected = false;
    console.warn(`[Kafka] Consumer connection failed (${err.message}). Retrying in 5 seconds...`);
    setTimeout(startKafkaConsumer, 5000);
  }
}

// Start Kafka consumer in background
startKafkaConsumer();

// ==============================================================================
// HTTP & SSE Server
// ==============================================================================
const PUBLIC_DIR = path.join(__dirname, '..', 'public');
const INDEX_HTML_PATH = path.join(PUBLIC_DIR, 'index.html');

const server = http.createServer((req, res) => {
  const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = parsedUrl.pathname;

  // CORS headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  // 1. Web Page
  if (req.method === 'GET' && (pathname === '/' || pathname === '/index.html')) {
    if (fs.existsSync(INDEX_HTML_PATH)) {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      fs.createReadStream(INDEX_HTML_PATH).pipe(res);
    } else {
      res.writeHead(200, { 'Content-Type': 'text/plain' });
      res.end('Notifier Service running. UI not found.');
    }
    return;
  }

  // 2. Server-Sent Events stream: GET /events
  if (req.method === 'GET' && pathname === '/events') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      'Connection': 'keep-alive'
    });

    sseClients.add(res);
    console.log(`[SSE] Client connected. Total active SSE clients: ${sseClients.size}`);

    // Send initial snapshot of all recorded notifications
    res.write(`event: init\ndata: ${JSON.stringify(notifications)}\n\n`);

    // Keepalive ping every 15s
    const pingInterval = setInterval(() => {
      res.write(': ping\n\n');
    }, 15000);

    req.on('close', () => {
      clearInterval(pingInterval);
      sseClients.delete(res);
      console.log(`[SSE] Client disconnected. Total active SSE clients: ${sseClients.size}`);
    });
    return;
  }

  // 3. API: List notifications: GET /api/notifications
  if (req.method === 'GET' && pathname === '/api/notifications') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      count: notifications.length,
      processedUniqueEvents: processedEventIds.size,
      notifications: notifications
    }));
    return;
  }

  // 4. API: Direct Ingestion / Webhook: POST /api/notifications/ingest
  if (req.method === 'POST' && pathname === '/api/notifications/ingest') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const payload = JSON.parse(body);
        const result = processEventIdempotently(payload);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(result));
      } catch (err) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: err.message }));
      }
    });
    return;
  }

  // 5. Health Check: GET /health or /actuator/health
  if (req.method === 'GET' && (pathname === '/health' || pathname === '/actuator/health')) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status: 'UP',
      kafka: {
        connected: isKafkaConnected,
        brokers: config.kafka.brokers,
        groupId: config.kafka.groupId,
        topic: config.kafka.topic
      },
      stats: {
        uniqueProcessedEventIds: processedEventIds.size,
        totalStoredNotifications: notifications.length,
        activeSseClients: sseClients.size
      }
    }));
    return;
  }

  // 6. Reset Store (for testing): POST /api/clear
  if (req.method === 'POST' && pathname === '/api/clear') {
    processedEventIds.clear();
    notifications = [];
    saveStore();
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ cleared: true }));
    return;
  }

  // 404 fallback
  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: 'Not Found', path: pathname }));
});

server.listen(config.port, () => {
  console.log(`[Notifier] HTTP & SSE server listening on port ${config.port}`);
  console.log(`[Notifier] UI available at: http://localhost:${config.port}/`);
  console.log(`[Notifier] SSE stream at: http://localhost:${config.port}/events`);
});

// Graceful shutdown
process.on('SIGTERM', async () => {
  console.log('[Notifier] SIGTERM received. Closing resources...');
  try {
    await consumer.disconnect();
  } catch (_) {}
  server.close(() => {
    console.log('[Notifier] Server terminated gracefully.');
    process.exit(0);
  });
});
