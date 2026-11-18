/**
 * Publishing policy: maps (breaking findings, consumer impact, semver bump) onto the
 * five-rule gate table, and computes the separate semver version-policy findings channel
 * (breaking-change-under-minor etc. never gate publication by themselves).
 *
 * Responsibility boundary: performs no diffs and no I/O.
 */
package com.contractguard.policy;
