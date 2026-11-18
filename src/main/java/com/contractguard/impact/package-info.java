/**
 * Consumer impact analysis: for each breaking compatibility finding, determines which
 * registered consumers are affected (AFFECTED / POSSIBLE / NONE) with evidence lines.
 *
 * Responsibility boundary: never decides publish gating.
 */
package com.contractguard.impact;
