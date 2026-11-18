/**
 * Specification ingestion: Swagger Parser wrapper, collect-all structural validation
 * (including every local $ref), canonicalization, effective-schema extraction per wire
 * location, and deprecation/x-sunset scanning.
 *
 * Responsibility boundary: knows nothing about consumers, compatibility classification,
 * policy or persistence.
 */
package com.contractguard.specs;
