/**
 * Webhook notifications to the untouched starter-kit receiver (HTTP POST,
 * fire-and-forget with one retry). Delivery failure is logged in the notifications
 * table and never blocks the publishing pipeline.
 */
package com.contractguard.notify;
