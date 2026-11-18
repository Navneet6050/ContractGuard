/**
 * Publication pipeline orchestration: receive → parse → validate → duplicate/regression
 * check → canonicalize → diff → version policy → deprecation scan → impact → gate →
 * audit → notify. Owns transaction boundaries and I/O ordering only; all analysis is
 * delegated to the core packages.
 */
package com.contractguard.pipeline;
