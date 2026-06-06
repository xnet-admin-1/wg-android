package device

import (
	"encoding/binary"
	"net/netip"
	"sync"
	"time"
)

type natKey struct {
	proto   uint8
	srcPort uint16
	dstIP   netip.Addr
	dstPort uint16
}

type natEntry struct {
	originalSrc netip.Addr
	lastSeen    int64
}

type TetherNAT struct {
	mu         sync.RWMutex
	entries    map[natKey]natEntry
	tetherNets []netip.Prefix
	vpnIP      netip.Addr
	ttl        int64
	respQueue  chan []byte
}

func NewTetherNAT(vpnIP netip.Addr, nets []netip.Prefix) *TetherNAT {
	t := &TetherNAT{
		entries:    make(map[natKey]natEntry, 4096),
		tetherNets: nets,
		vpnIP:      vpnIP,
		ttl:        300,
		respQueue:  make(chan []byte, 256),
	}
	go t.cleanup()
	return t
}

func (t *TetherNAT) EnqueueResponse(pkt []byte) {
	select {
	case t.respQueue <- pkt:
	default: // drop if full
	}
}

func (t *TetherNAT) DequeueResponse() []byte {
	select {
	case pkt := <-t.respQueue:
		return pkt
	default:
		return nil
	}
}

func (t *TetherNAT) isTether(ip netip.Addr) bool {
	for _, p := range t.tetherNets {
		if p.Contains(ip) {
			return true
		}
	}
	return false
}

func (t *TetherNAT) RewriteOutbound(pkt []byte) bool {
	if len(pkt) < 20 || pkt[0]>>4 != 4 {
		return false
	}
	srcIP := netip.AddrFrom4([4]byte(pkt[12:16]))
	if srcIP == t.vpnIP || !t.isTether(srcIP) {
		return false
	}
	ihl := int(pkt[0]&0x0f) * 4
	proto := pkt[9]
	var srcPort, dstPort uint16
	if (proto == 6 || proto == 17) && len(pkt) >= ihl+4 {
		srcPort = binary.BigEndian.Uint16(pkt[ihl : ihl+2])
		dstPort = binary.BigEndian.Uint16(pkt[ihl+2 : ihl+4])
	}
	dstIP := netip.AddrFrom4([4]byte(pkt[16:20]))
	key := natKey{proto: proto, srcPort: srcPort, dstIP: dstIP, dstPort: dstPort}
	t.mu.Lock()
	t.entries[key] = natEntry{originalSrc: srcIP, lastSeen: time.Now().Unix()}
	t.mu.Unlock()
	v := t.vpnIP.As4()
	copy(pkt[12:16], v[:])
	pkt[8] = 64
	fixIPChecksum(pkt[:ihl])
	if proto == 6 && len(pkt) >= ihl+20 {
		fixTCPChecksum(pkt, ihl)
	} else if proto == 17 && len(pkt) >= ihl+8 {
		fixUDPChecksum(pkt, ihl)
	}
	return true
}

func (t *TetherNAT) RewriteInbound(pkt []byte) bool {
	if len(pkt) < 20 || pkt[0]>>4 != 4 {
		return false
	}
	dstIP := netip.AddrFrom4([4]byte(pkt[16:20]))
	if dstIP != t.vpnIP {
		return false
	}
	ihl := int(pkt[0]&0x0f) * 4
	proto := pkt[9]
	var srcPort, dstPort uint16
	if (proto == 6 || proto == 17) && len(pkt) >= ihl+4 {
		srcPort = binary.BigEndian.Uint16(pkt[ihl : ihl+2])
		dstPort = binary.BigEndian.Uint16(pkt[ihl+2 : ihl+4])
	}
	srcIP := netip.AddrFrom4([4]byte(pkt[12:16]))
	key := natKey{proto: proto, srcPort: dstPort, dstIP: srcIP, dstPort: srcPort}
	t.mu.RLock()
	entry, ok := t.entries[key]
	t.mu.RUnlock()
	if !ok {
		return false
	}
	orig := entry.originalSrc.As4()
	copy(pkt[16:20], orig[:])
	fixIPChecksum(pkt[:ihl])
	if proto == 6 && len(pkt) >= ihl+20 {
		fixTCPChecksum(pkt, ihl)
	} else if proto == 17 && len(pkt) >= ihl+8 {
		fixUDPChecksum(pkt, ihl)
	}
	return true
}

func (t *TetherNAT) cleanup() {
	for {
		time.Sleep(30 * time.Second)
		now := time.Now().Unix()
		t.mu.Lock()
		for k, v := range t.entries {
			if now-v.lastSeen > t.ttl {
				delete(t.entries, k)
			}
		}
		t.mu.Unlock()
	}
}

func fixIPChecksum(hdr []byte) {
	hdr[10] = 0
	hdr[11] = 0
	var sum uint32
	for i := 0; i < len(hdr)-1; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(hdr[i : i+2]))
	}
	if len(hdr)%2 == 1 {
		sum += uint32(hdr[len(hdr)-1]) << 8
	}
	for sum > 0xffff {
		sum = (sum >> 16) + (sum & 0xffff)
	}
	binary.BigEndian.PutUint16(hdr[10:12], ^uint16(sum))
}

func fixTCPChecksum(pkt []byte, ihl int) {
	pkt[ihl+16] = 0
	pkt[ihl+17] = 0
	binary.BigEndian.PutUint16(pkt[ihl+16:ihl+18], pseudoChecksum(pkt, ihl))
}

func fixUDPChecksum(pkt []byte, ihl int) {
	pkt[ihl+6] = 0
	pkt[ihl+7] = 0
	csum := pseudoChecksum(pkt, ihl)
	if csum == 0 {
		csum = 0xffff
	}
	binary.BigEndian.PutUint16(pkt[ihl+6:ihl+8], csum)
}

func pseudoChecksum(pkt []byte, ihl int) uint16 {
	length := len(pkt) - ihl
	var sum uint32
	sum += uint32(binary.BigEndian.Uint16(pkt[12:14]))
	sum += uint32(binary.BigEndian.Uint16(pkt[14:16]))
	sum += uint32(binary.BigEndian.Uint16(pkt[16:18]))
	sum += uint32(binary.BigEndian.Uint16(pkt[18:20]))
	sum += uint32(pkt[9])
	sum += uint32(length)
	for i := ihl; i < len(pkt)-1; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(pkt[i : i+2]))
	}
	if len(pkt)%2 == 1 {
		sum += uint32(pkt[len(pkt)-1]) << 8
	}
	for sum > 0xffff {
		sum = (sum >> 16) + (sum & 0xffff)
	}
	return ^uint16(sum)
}

func (d *Device) SetTetherNAT(nat *TetherNAT) {
	d.tethNAT = nat
}
