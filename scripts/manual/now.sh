#!/usr/bin/env bash
# Wall-clock stamp: epoch + HH:MM:SS. Used to bracket the phase-2 agent orchestration for timing,
# as one bare command that an agent allow-rule can cover (bare `date` usually is not allowed).
date '+%s  %H:%M:%S'
