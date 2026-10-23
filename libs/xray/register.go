package xraybridge

// On-demand registration of the Xray-core components this bridge needs.
// Deliberately not main/distro/all: that pulls in VLESS/Trojan/WireGuard/
// Hysteria, DNS, metrics, commander and every command line tool.
//
// main/json imports infra/conf, which in turn registers the full JSON config
// surface (vless/trojan/wireguard/hysteria among them). Trimming that would
// mean hand-building protobuf configs; not worth it here.
import (
	_ "github.com/xtls/xray-core/app/dispatcher"
	_ "github.com/xtls/xray-core/app/proxyman/inbound"
	_ "github.com/xtls/xray-core/app/proxyman/outbound"

	_ "github.com/xtls/xray-core/main/json"

	_ "github.com/xtls/xray-core/proxy/shadowsocks"
	_ "github.com/xtls/xray-core/proxy/shadowsocks_2022"
	_ "github.com/xtls/xray-core/proxy/socks"
	_ "github.com/xtls/xray-core/proxy/vmess/outbound"

	_ "github.com/xtls/xray-core/transport/internet/tcp"
	_ "github.com/xtls/xray-core/transport/internet/tls"
	_ "github.com/xtls/xray-core/transport/internet/udp"
	_ "github.com/xtls/xray-core/transport/internet/websocket"
)
