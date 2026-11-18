/**
 * Publication attempt state machine: received → rejected | auto_published | pending_ack |
 * blocked, then pending_ack → published | abandoned, blocked → overridden_published |
 * abandoned. Transitions are guarded; ack requires only pending_ack, override requires
 * blocked plus a non-blank justification.
 *
 * Responsibility boundary: I/O only through interfaces.
 */
package com.contractguard.domain;
