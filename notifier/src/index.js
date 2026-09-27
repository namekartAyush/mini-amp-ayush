const fs = require('fs');
const path = require('path');

// Load base environment file
require('dotenv').config();

function readSecret(envVar, fileVar, defaultVal = '') {
  if (process.env[fileVar] && fs.existsSync(process.env[fileVar])) {
    return fs.readFileSync(process.env[fileVar], 'utf8').trim();
  }
  return process.env[envVar] || defaultVal;
}

// Strict fail-fast configuration schema validation
function loadAndValidateConfig() {
  const nodeEnv = process.env.NODE_ENV || 'development';
  const port = parseInt(process.env.PORT || '3001', 10);
  const kafkaBrokers = process.env.KAFKA_BROKERS || 'localhost:9092';
  const kafkaGroupId = process.env.KAFKA_GROUP_ID || 'notifier-group';
  const kafkaTopic = process.env.KAFKA_TOPIC_AUCTION_EVENTS || 'auction-events';

  const smtpHost = process.env.SMTP_HOST;
  const smtpPort = parseInt(process.env.SMTP_PORT || '587', 10);
  const smtpUser = process.env.SMTP_USER;
  const smtpPassword = readSecret('SMTP_PASSWORD', 'SMTP_PASSWORD_FILE');

  // Enforce fail-fast rules in production
  if (nodeEnv === 'production') {
    const missing = [];
    if (!process.env.KAFKA_BROKERS) missing.push('KAFKA_BROKERS');
    if (!smtpHost) missing.push('SMTP_HOST');
    if (!smtpUser) missing.push('SMTP_USER');
    if (!smtpPassword) missing.push('SMTP_PASSWORD (or SMTP_PASSWORD_FILE)');

    if (missing.length > 0) {
      console.error('FATAL: Application failed to start due to missing configuration:');
      missing.forEach(m => console.error(`  - ${m}`));
      process.exit(1);
    }
  }

  return {
    nodeEnv,
    port,
    kafka: { brokers: kafkaBrokers.split(','), groupId: kafkaGroupId, topic: kafkaTopic },
    smtp: { host: smtpHost, port: smtpPort, user: smtpUser, password: smtpPassword ? '******' : null }
  };
}

const config = loadAndValidateConfig();
console.log('Notifier Service configuration validated successfully:', JSON.stringify(config, null, 2));
