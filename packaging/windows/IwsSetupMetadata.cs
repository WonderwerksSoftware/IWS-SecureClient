using System;
using System.IO;
using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;
using System.Text;
using System.Text.RegularExpressions;

internal sealed class IwsSetupManifest {
    internal string DeviceId { get; set; }
    internal int Generation { get; set; }
    internal string ClientHostname { get; set; }
    internal string ClientCheckpoint { get; set; }
    internal DateTime ExpiresUtc { get; set; }
}

internal sealed class IwsInstallationReceipt {
    internal string DeviceId { get; set; }
    internal int Generation { get; set; }
    internal string ClientCheckpoint { get; set; }
}

[DataContract]
internal sealed class IwsReleaseProvenanceDto {
    [DataMember(Name = "releaseTag")] public string ReleaseTag { get; set; }
    [DataMember(Name = "sourceCommit")] public string SourceCommit { get; set; }
    [DataMember(Name = "tagObject")] public string TagObject { get; set; }
}

[DataContract]
internal sealed class IwsManifestDto {
    [DataMember(Name = "deviceId")] public string DeviceId { get; set; }
    [DataMember(Name = "generation")] public int Generation { get; set; }
    [DataMember(Name = "platform")] public string Platform { get; set; }
    [DataMember(Name = "clientHostname")] public string ClientHostname { get; set; }
    [DataMember(Name = "clientCheckpoint")] public string ClientCheckpoint { get; set; }
    [DataMember(Name = "expiresAt")] public string ExpiresAt { get; set; }
    [DataMember(Name = "management_server")] public string ManagementServer { get; set; }
    [DataMember(Name = "iws_entrypoint")] public string IwsEntrypoint { get; set; }
    [DataMember(Name = "releaseProvenance")] public IwsReleaseProvenanceDto ReleaseProvenance { get; set; }
}

[DataContract]
internal sealed class IwsReceiptDto {
    [DataMember(Name = "schemaVersion")] public int SchemaVersion { get; set; }
    [DataMember(Name = "deviceId")] public string DeviceId { get; set; }
    [DataMember(Name = "generation")] public int Generation { get; set; }
    [DataMember(Name = "clientCheckpoint")] public string ClientCheckpoint { get; set; }
}

internal static class IwsSetupMetadata {
    private static readonly Regex DeviceId = new Regex("^[a-z0-9]{1,40}$", RegexOptions.CultureInvariant);
    private static readonly Regex ObjectId = new Regex("^[0-9a-f]{40}$", RegexOptions.CultureInvariant);
    private static readonly Regex Release = new Regex("^secure-client-v[0-9]+[.][0-9]+[.][0-9]+$",
        RegexOptions.CultureInvariant);

    internal static IwsSetupManifest ParseManifest(string json) {
        IwsManifestDto dto = Deserialize<IwsManifestDto>(json);
        DateTimeOffset expires;
        if (dto == null) throw new InvalidDataException("IWS manifest is empty.");
        if (!DeviceId.IsMatch(dto.DeviceId ?? "") || dto.Generation < 1 ||
            dto.Platform != "WINDOWS" || dto.ClientHostname != "iws-" + dto.DeviceId + "-g" + dto.Generation)
            throw new InvalidDataException("IWS manifest device identity is invalid.");
        if (dto.ClientCheckpoint != "secure-client-v1.0.1" || dto.ReleaseProvenance == null ||
            dto.ReleaseProvenance.ReleaseTag != dto.ClientCheckpoint ||
            !ObjectId.IsMatch(dto.ReleaseProvenance.SourceCommit ?? "") ||
            !ObjectId.IsMatch(dto.ReleaseProvenance.TagObject ?? ""))
            throw new InvalidDataException("IWS manifest release provenance is invalid.");
        if (dto.ManagementServer != "https://api.netbird.io:443" ||
            dto.IwsEntrypoint != "https://portal.iws.internal/")
            throw new InvalidDataException("IWS manifest endpoint policy is invalid.");
        if (!DateTimeOffset.TryParse(dto.ExpiresAt, out expires))
            throw new InvalidDataException("IWS manifest expiry is invalid.");
        return new IwsSetupManifest {
            DeviceId = dto.DeviceId,
            Generation = dto.Generation,
            ClientHostname = dto.ClientHostname,
            ClientCheckpoint = dto.ClientCheckpoint,
            ExpiresUtc = expires.UtcDateTime
        };
    }

    internal static IwsInstallationReceipt ParseReceipt(string json) {
        IwsReceiptDto dto = Deserialize<IwsReceiptDto>(json);
        if (dto == null || dto.SchemaVersion != 1 || !DeviceId.IsMatch(dto.DeviceId ?? "") ||
            dto.Generation < 1 || !Release.IsMatch(dto.ClientCheckpoint ?? "")) {
            throw new InvalidDataException("IWS installation receipt is invalid.");
        }
        return new IwsInstallationReceipt {
            DeviceId = dto.DeviceId,
            Generation = dto.Generation,
            ClientCheckpoint = dto.ClientCheckpoint
        };
    }

    private static T Deserialize<T>(string json) where T : class {
        if (String.IsNullOrWhiteSpace(json)) throw new InvalidDataException();
        try {
            DataContractJsonSerializer serializer = new DataContractJsonSerializer(typeof(T));
            using (MemoryStream stream = new MemoryStream(Encoding.UTF8.GetBytes(json))) {
                return serializer.ReadObject(stream) as T;
            }
        }
        catch (Exception exception) {
            throw new InvalidDataException("IWS JSON metadata is invalid.", exception);
        }
    }
}
