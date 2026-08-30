/**
 * Ingestion module — owns all RBI Account Aggregator (and other raw feed) specifics: consent,
 * fetch sessions, raw records, normalisation. Emits a clean normalised event stream; nothing
 * downstream depends on AA SDK types. See {@code docs/product/DOMAIN_MODEL.md} section 2.
 */
package com.rohit.nyvra.ingestion;
