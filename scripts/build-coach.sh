#!/bin/sh
# Build on coach.
#
#   scripts/build-coach.sh                                    # default target
#   scripts/build-coach.sh shell -c 'bitbake microduck-daemon'
#   scripts/build-coach.sh shell -c 'bitbake -c update_crates microduck-daemon'
#
# Exists so the kas-container invocation lives in one place. Composing it inline
# over ssh needs three levels of quoting (ssh '...', sh -c "...", --runtime-args
# "...") and silently truncates when one is wrong -- which produced a build that
# wrote a zero-byte log and never started a container.
#
# --ssh-agent forwards the auth socket for the private microduck_daemon fetch, and
# validates an agent is actually reachable, so connect with `ssh -A coach`.
# KAS_SSH_DIR (set in kas/coach.env) supplies known_hosts.
#
# The memory cap and kas/limited-ram.yml are there because coach's RAM fault is only
# partly mitigated: memtest=4 reserves the bad region at 30.5 GiB, but another lives
# above ~50 GB. See CONTEXT.md in the microduck_os folder. Drop both -- the
# --runtime-args line here and the overlay -- once the DRAM is set to 4800 MT/s and
# `stressapptest -M 55000 -s 300 -W` comes back clean.
set -eu

cd "$(dirname "$0")/.."
. kas/coach.env

CMD=${1:-build}
[ $# -gt 0 ] && shift

exec "$HOME"/bin/kas-container \
	--ssh-agent \
	--runtime-args "--memory=28g --memory-swap=28g" \
	"$CMD" kas/microduck.yml:kas/limited-ram.yml "$@"
