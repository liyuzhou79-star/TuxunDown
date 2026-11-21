package com.tuxundown.android.core

// v1.2 removed the exporter: downloads are no longer staged in the
// app's private directory and copied out when finished. Bytes are
// written directly into the public Download folder (or the user's
// SAF folder) as ".part" files and renamed in place on completion —
// see PublicStorage. This file is intentionally left empty so the
// old export path cannot be resurrected by accident.
