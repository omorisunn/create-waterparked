import struct, zlib, sys, os

MCA = r"C:\Users\David\Desktop\CreateZ.io\Mods-development\Create-Waterparked\run\saves\Showwwwwcase\region\r.-2.-1.mca"

# anchor world coords
AX, AY, AZ = -624, 72, -62
CHUNK_X = AX >> 4  # -39
CHUNK_Z = AZ >> 4  # -4

with open(MCA, "rb") as f:
    # 8KB header: 1024 chunk locations + 1024 timestamps
    loc = struct.unpack(">1024I", f.read(4096))
    ts = struct.unpack(">1024I", f.read(4096))
    sidx = (CHUNK_X % 32) + (CHUNK_Z % 32) * 32
    off = loc[sidx]
    size = off & 0xFF
    sector = off >> 8
    if size == 0 or sector == 0:
        print("chunk not present in this region")
        sys.exit(1)
    f.seek(sector * 4096)
    clen = struct.unpack(">I", f.read(4))[0]
    ctype = struct.unpack("B", f.read(1))[0]
    data = f.read(clen - 1)
    if ctype == 2:
        data = zlib.decompress(data)
    elif ctype == 3:
        data = zlib.decompress(data)[1]  # not actually valid; short read
    print("compression=", ctype, "len=", len(data))

# ---------------- minimal NBT reader ----------------
class NBTReader:
    def __init__(self, buf):
        self.b = buf
        self.p = 0
    def u8(self):
        v = self.b[self.p]; self.p += 1; return v
    def u16(self):
        v = struct.unpack_from(">H", self.b, self.p)[0]; self.p += 2; return v
    def u32(self):
        v = struct.unpack_from(">I", self.b, self.p)[0]; self.p += 4; return v
    def i8(self):
        v = struct.unpack_from(">b", self.b, self.p)[0]; self.p += 1; return v
    def i16(self):
        v = struct.unpack_from(">h", self.b, self.p)[0]; self.p += 2; return v
    def i32(self):
        v = struct.unpack_from(">i", self.b, self.p)[0]; self.p += 4; return v
    def i64(self):
        v = struct.unpack_from(">q", self.b, self.p)[0]; self.p += 8; return v
    def f32(self):
        v = struct.unpack_from(">f", self.b, self.p)[0]; self.p += 4; return v
    def f64(self):
        v = struct.unpack_from(">d", self.b, self.p)[0]; self.p += 8; return v
    def buf(self, n):
        v = self.b[self.p:self.p+n]; self.p += n; return v
    def skip_payload(self, tag):
        if tag == 1: self.p += 1
        elif tag == 2: self.p += 2
        elif tag == 3: self.p += 4
        elif tag == 4: self.p += 8
        elif tag == 5: self.p += 4
        elif tag == 6: self.p += 8
        elif tag == 7:
            n = self.i32(); self.p += n
        elif tag == 8:
            n = self.u16(); self.p += n
        elif tag == 9:
            et = self.u8(); n = self.i32()
            for _ in range(n): self.skip_payload(et)
        elif tag == 10:
            while True:
                t = self.u8()
                if t == 0: break
                name_len = self.u16(); self.p += name_len
                self.skip_payload(t)
        elif tag == 11: self.p += self.i32() * 4
        elif tag == 12: self.p += self.i32() * 8
        else: raise Exception(f"unknown tag {tag}")
    def named(self):
        t = self.u8()
        name_len = self.u16()
        name = self.b[self.p:self.p+name_len].decode("utf-8", "replace")
        self.p += name_len
        return t, name
    def value(self):
        t = self.u8()
        return t, self._val(t)
    def _val(self, t):
        if t == 1: return self.i8()
        if t == 2: return self.i16()
        if t == 3: return self.i32()
        if t == 4: return self.i64()
        if t == 5: return self.f32()
        if t == 6: return self.f64()
        if t == 7:
            n = self.i32(); return list(self.b[self.p:self.p+n]); self.p += n
        if t == 8:
            n = self.u16(); v = self.b[self.p:self.p+n].decode("utf-8","replace"); self.p += n; return v
        if t == 9:
            et = self.u8(); n = self.i32(); return [(et, self._val(et)) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                tt = self.u8()
                if tt == 0: break
                nl = self.u16(); nm = self.b[self.p:self.p+nl].decode("utf-8","replace"); self.p += nl
                d[nm] = self._val(tt)
            return d
        if t == 11:
            n = self.i32(); return [self.i32() for _ in range(n)]
        if t == 12:
            n = self.i32(); return [self.i64() for _ in range(n)]
        raise Exception(f"unknown tag {t}")

r = NBTReader(data)
t, root = r.value()
print("root tag:", t)
# root is a compound; find 'sections' / block entities
def walk(node, path="", depth=0):
    if depth > 3: return
    if isinstance(node, dict):
        for k, v in node.items():
            if k in ("block_entities", "sections", "blockEntities", "block_ticks") or depth >= 2:
                print("  " * depth + f"{k}: {type(v).__name__}({len(v) if hasattr(v,'__len__') else ''})")
            walk(v, k, depth+1)

walk(root)

# find block entities: sections[].block_entities or root block_entities
def find_bes(node):
    out = []
    if isinstance(node, dict):
        for k, v in node.items():
            if k in ("block_entities", "blockEntities", "BlockEntities") and isinstance(v, list):
                for e in v:
                    if isinstance(e, dict):
                        out.append(e)
            else:
                out.extend(find_bes(v))
    return out

bes = find_bes(root)
print("total block entities:", len(bes))
found = 0
for be in bes:
    try:
        x = be.get("x"); y = be.get("y"); z = be.get("z")
        if x == AX and y == AY and z == AZ:
            found += 1
            print("=== FOUND anchor BE ===")
            print("id:", be.get("id"))
            for k in sorted(be.keys()):
                v = be[k]
                if k == "Sectors" and isinstance(v, list):
                    print(" Sectors:")
                    for i, s in enumerate(v):
                        if isinstance(s, tuple):
                            s = s[1]
                        print("   [" + str(i) + "] " + str(s))
                else:
                    print(" ", k, "=", v if not isinstance(v, (bytes, list)) else (len(v)))
    except Exception as ex:
        print("err", ex)
if not found:
    print("anchor not found in this region; scanning BE ids...")
    ids = {}
    for be in bes:
        ids[be.get("id")] = ids.get(be.get("id"), 0) + 1
    print(ids)