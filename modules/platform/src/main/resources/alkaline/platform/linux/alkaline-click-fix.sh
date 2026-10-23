#!/bin/sh
# Alkaline click fix: stops libinput from debouncing mouse buttons, system-wide.
#
# libinput (inside every X server and Wayland compositor) drops a button release and the next press that
# arrive within 25 ms of the previous button event, treating them as switch bounce. Fast butterfly and drag
# clicking produce exactly that, so those clicks never reach Minecraft or any other application. libinput skips
# debouncing for devices carrying its ModelBouncingKeys quirk (upstream sets it for some mice itself); this
# script adds that quirk for all mice to the administrator's override file, inside markers so it can be removed
# again without touching anything else in the file.
#
# Usage, as root (for example: pkexec /bin/sh alkaline-click-fix.sh install):
#   install   add the quirk          remove   take it out again          status   report whether it is there
# Takes effect when the mouse is reconnected or after logging out and back in.
set -eu

FILE="${ALKALINE_QUIRKS_FILE:-/etc/libinput/local-overrides.quirks}"
BEGIN="# >>> Alkaline click fix >>>"
END="# <<< Alkaline click fix <<<"

installed() {
    [ -f "$FILE" ] && grep -qxF "$BEGIN" "$FILE"
}

case "${1:-status}" in
install)
    if installed; then
        echo "Alkaline click fix: already installed in $FILE"
        exit 0
    fi
    mkdir -p "$(dirname "$FILE")"
    {
        if [ -s "$FILE" ]; then
            echo
        fi
        echo "$BEGIN"
        echo "[Alkaline: no mouse button debouncing]"
        echo "MatchUdevType=mouse"
        echo "ModelBouncingKeys=1"
        echo "$END"
    } >> "$FILE"
    chmod 644 "$FILE"
    echo "Alkaline click fix: installed in $FILE. Reconnect the mouse or log out and back in to apply it."
    ;;
remove)
    if ! installed; then
        echo "Alkaline click fix: not installed"
        exit 0
    fi
    temporary="$(mktemp "$FILE.XXXXXX")"
    awk -v begin="$BEGIN" -v end="$END" '
        # Drop the blank line install put before the section.
        $0 == "" && !skipping { blank++; next }
        $0 == begin { skipping = 1; blank = 0; next }
        $0 == end { skipping = 0; next }
        !skipping { while (blank > 0) { print ""; blank-- } print }
    ' "$FILE" > "$temporary"
    chmod 644 "$temporary"
    if grep -q '[^[:space:]]' "$temporary"; then
        mv "$temporary" "$FILE"
    else
        rm -f "$temporary" "$FILE"
    fi
    echo "Alkaline click fix: removed from $FILE. Reconnect the mouse or log out and back in to apply it."
    ;;
status)
    if installed; then
        echo "Alkaline click fix: installed in $FILE"
    else
        echo "Alkaline click fix: not installed"
    fi
    ;;
*)
    echo "usage: $0 install|remove|status" >&2
    exit 2
    ;;
esac
