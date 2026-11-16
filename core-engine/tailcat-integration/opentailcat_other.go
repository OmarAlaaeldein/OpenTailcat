// Copyright (c) OpenTailcat contributors
// SPDX-License-Identifier: BSD-3-Clause

//go:build !android

package tailcat

import "tailscale.com/net/netns"

func configureStandaloneNetNS() { netns.SetEnabled(false) }
