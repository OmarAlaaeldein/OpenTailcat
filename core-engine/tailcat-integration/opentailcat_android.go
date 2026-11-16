// Copyright (c) OpenTailcat contributors
// SPDX-License-Identifier: BSD-3-Clause

//go:build android

package tailcat

import "tailscale.com/net/netns"

// Android transport sockets must always receive the registered protect hook,
// including initial STUN, DERP and Magicsock sockets and all later redials.
func configureStandaloneNetNS() { netns.SetEnabled(true) }
