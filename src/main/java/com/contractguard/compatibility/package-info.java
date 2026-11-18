/**
 * Compatibility engine: independent schema-level diff between two effective schema maps,
 * classified into stable rule codes with severity (breaking/warn/info), per the
 * guarantee-asymmetry principle in PLAN.md §6. Cosmetic reorganization must produce no
 * breaking findings.
 *
 * Responsibility boundary: knows nothing about consumers, publishing policy or versions.
 */
package com.contractguard.compatibility;
