// Client address behind Cloudflare + Render. Both append to X-Forwarded-For, so a fixed hop count is fragile:
// we walk the header from the right and skip proxy addresses (Cloudflare edge ranges, private/loopback),
// returning the first address that is not a known proxy. Anything an attacker forges sits to the LEFT of the
// real client address, so it can never be picked. Falls back to the socket peer.
import net from 'node:net';

const list = new net.BlockList();
for (const c of `173.245.48.0/20 103.21.244.0/22 103.22.200.0/22 103.31.4.0/22 141.101.64.0/18 108.162.192.0/18
190.93.240.0/20 188.114.96.0/20 197.234.240.0/22 198.41.128.0/17 162.158.0.0/15 104.16.0.0/13 104.24.0.0/14
172.64.0.0/13 131.0.72.0/22 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 100.64.0.0/10 127.0.0.0/8 169.254.0.0/16`.split(/\s+/)) {
  const [a, p] = c.split('/'); list.addSubnet(a, Number(p), 'ipv4');
}
for (const c of '2400:cb00::/32 2606:4700::/32 2803:f800::/32 2405:b500::/32 2405:8100::/32 2a06:98c0::/29 2c0f:f248::/32 fc00::/7 fe80::/10'.split(' ')) {
  const [a, p] = c.split('/'); list.addSubnet(a, Number(p), 'ipv6');
}

const norm = (s) => String(s).trim().replace(/^::ffff:/i, '');
export function isProxyAddress(addr) {
  const a = norm(addr);
  const fam = net.isIP(a);
  if (!fam) return true; // garbage is never a client
  if (a === '::1') return true;
  return list.check(a, fam === 4 ? 'ipv4' : 'ipv6');
}

export function clientIp(req, { behindProxy }) {
  const peer = norm(req.socket?.remoteAddress || '') || 'unknown';
  if (!behindProxy) return peer;
  const xff = String(req.headers['x-forwarded-for'] || '').split(',').map((s) => s.trim()).filter(Boolean);
  for (let i = xff.length - 1; i >= 0; i--) {
    if (net.isIP(norm(xff[i])) && !isProxyAddress(xff[i])) return norm(xff[i]);
  }
  return peer;
}
