package engine

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"

	"tailscale.com/tailcfg"
)

var errUnsafeDERPMap = errors.New("fetched DERP map had no safe nodes")

type derpFilterTransport struct {
	base http.RoundTripper
}

func (t *derpFilterTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	base := t.base
	if base == nil {
		base = http.DefaultTransport
	}
	res, err := base.RoundTrip(req)
	if err != nil || req == nil || req.URL == nil {
		return res, err
	}
	if res == nil || res.StatusCode != http.StatusOK {
		return res, err
	}
	path := strings.ToLower(req.URL.Path)
	if !strings.Contains(path, "derpmap") {
		return res, err
	}
	body, readErr := io.ReadAll(io.LimitReader(res.Body, 8<<20))
	res.Body.Close()
	if readErr != nil {
		return nil, readErr
	}
	filtered, filterErr := filterFetchedDERPMapJSON(body)
	if filterErr != nil {
		return &http.Response{
			StatusCode: http.StatusBadGateway,
			Status:     "502 Bad Gateway",
			Proto:      res.Proto,
			ProtoMajor: res.ProtoMajor,
			ProtoMinor: res.ProtoMinor,
			Header:     make(http.Header),
			Body:       io.NopCloser(bytes.NewReader(nil)),
			Request:    req,
		}, nil
	}
	out := *res
	out.Body = io.NopCloser(bytes.NewReader(filtered))
	out.ContentLength = int64(len(filtered))
	out.Header = res.Header.Clone()
	out.Header.Set("Content-Length", strconv.Itoa(len(filtered)))
	return &out, nil
}

func filterFetchedDERPMapJSON(raw []byte) ([]byte, error) {
	dm := new(tailcfg.DERPMap)
	if err := json.Unmarshal(raw, dm); err != nil {
		return nil, err
	}
	if err := sanitizeFetchedDERPMap(dm); err != nil {
		return nil, err
	}
	return json.Marshal(dm)
}

func sanitizeFetchedDERPMap(dm *tailcfg.DERPMap) error {
	if dm == nil || len(dm.Regions) == 0 {
		return errUnsafeDERPMap
	}
	for id, region := range dm.Regions {
		if region == nil {
			delete(dm.Regions, id)
			continue
		}
		kept := region.Nodes[:0]
		for _, n := range region.Nodes {
			if n == nil || n.InsecureForTests {
				continue
			}
			if rejectUnsafeDERPEndpoint("hostname", n.HostName) != nil {
				continue
			}
			if rejectUnsafeDERPEndpoint("IPv4", n.IPv4) != nil {
				continue
			}
			if rejectUnsafeDERPEndpoint("IPv6", n.IPv6) != nil {
				continue
			}
			kept = append(kept, n)
		}
		region.Nodes = kept
		if len(region.Nodes) == 0 {
			delete(dm.Regions, id)
		}
	}
	if len(dm.Regions) == 0 {
		return errUnsafeDERPMap
	}
	return nil
}
