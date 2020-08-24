package eu.europa.esig.dss.cades.signature;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.SignerInfoGeneratorBuilder;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.util.Store;

import eu.europa.esig.dss.cades.CMSUtils;
import eu.europa.esig.dss.cades.validation.CAdESSignature;
import eu.europa.esig.dss.cades.validation.CMSDocumentValidator;
import eu.europa.esig.dss.enumerations.ArchiveTimestampType;
import eu.europa.esig.dss.enumerations.SignatureAlgorithm;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.DSSException;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.SignatureValue;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.signature.BaselineBCertificateSelector;
import eu.europa.esig.dss.spi.DSSASN1Utils;
import eu.europa.esig.dss.utils.Utils;
import eu.europa.esig.dss.validation.AdvancedSignature;
import eu.europa.esig.dss.validation.CertificateVerifier;
import eu.europa.esig.dss.validation.timestamp.TimestampToken;

public class CAdESCounterSignatureBuilder {

	private final CertificateVerifier certificateVerifier;

	public CAdESCounterSignatureBuilder(CertificateVerifier certificateVerifier) {
		this.certificateVerifier = certificateVerifier;
	}

	public CMSSignedData recursivelyAddCounterSignature(CMSSignedData originalCMSSignedData, CAdESCounterSignatureParameters parameters,
			SignatureValue signatureValue) {

		final List<SignerInformation> updatedSignerInfo = getUpdatedSignerInformations(originalCMSSignedData, originalCMSSignedData.getSignerInfos(),
				parameters, signatureValue, null);

		if (Utils.isCollectionNotEmpty(updatedSignerInfo)) {
			CMSSignedData updatedCMSSignedData = CMSSignedData.replaceSigners(originalCMSSignedData, new SignerInformationStore(updatedSignerInfo));
			return addNewCertificates(updatedCMSSignedData, originalCMSSignedData, parameters);
		} else {
			throw new DSSException("No updated signed info");
		}
	}

	private List<SignerInformation> getUpdatedSignerInformations(CMSSignedData originalCMSSignedData, SignerInformationStore signerInformationStore,
			CAdESCounterSignatureParameters parameters, SignatureValue signatureValue, CAdESSignature masterSignature) {

		List<SignerInformation> result = new LinkedList<>();
		for (SignerInformation signerInformation : signerInformationStore) {
			CAdESSignature cades = new CAdESSignature(originalCMSSignedData, signerInformation);
			cades.setMasterSignature(masterSignature);
			if (Utils.areStringsEqual(cades.getId(), parameters.getSignatureIdToCounterSign())) {
				if (masterSignature != null) {
					throw new UnsupportedOperationException("Cannot recursively add a counter-signature");
				}
				if (containsATSTv2(cades)) {
					throw new DSSException("Cannot add a counter signature to a CAdES containing an archiveTimestampV2");
				}

				SignerInformationStore counterSignatureSignerInfoStore = generateCounterSignature(originalCMSSignedData, signerInformation, parameters,
						signatureValue);

				result.add(SignerInformation.addCounterSigners(signerInformation, counterSignatureSignerInfoStore));

			} else if (signerInformation.getCounterSignatures().size() > 0) {
				List<SignerInformation> updatedSignerInformations = getUpdatedSignerInformations(originalCMSSignedData,
						signerInformation.getCounterSignatures(), parameters, signatureValue, cades);
				result.add(SignerInformation.addCounterSigners(signerInformation, new SignerInformationStore(updatedSignerInformations)));
			} else {
				result.add(signerInformation);
			}
		}

		return result;
	}

	private CMSSignedData addNewCertificates(CMSSignedData updatedCMSSignedData, CMSSignedData originalCMSSignedData,
			CAdESCounterSignatureParameters parameters) {
		final List<CertificateToken> certificateTokens = new LinkedList<>();
		Store<X509CertificateHolder> certificatesStore = originalCMSSignedData.getCertificates();
		final Collection<X509CertificateHolder> certificatesMatches = certificatesStore.getMatches(null);
		for (final X509CertificateHolder certificatesMatch : certificatesMatches) {
			final CertificateToken token = DSSASN1Utils.getCertificate(certificatesMatch);
			if (!certificateTokens.contains(token)) {
				certificateTokens.add(token);
			}
		}

		BaselineBCertificateSelector certificateSelectors = new BaselineBCertificateSelector(certificateVerifier, parameters);
		List<CertificateToken> newCertificates = certificateSelectors.getCertificates();
		for (CertificateToken certificateToken : newCertificates) {
			if (!certificateTokens.contains(certificateToken)) {
				certificateTokens.add(certificateToken);
			}
		}

		final Collection<X509Certificate> certs = new ArrayList<>();
		for (final CertificateToken certificateInChain : certificateTokens) {
			certs.add(certificateInChain.getCertificate());
		}

		try {
			JcaCertStore jcaCertStore = new JcaCertStore(certs);
			return CMSSignedData.replaceCertificatesAndCRLs(updatedCMSSignedData, jcaCertStore, originalCMSSignedData.getAttributeCertificates(),
					originalCMSSignedData.getCRLs());
		} catch (Exception e) {
			throw new DSSException("Unable to create the JcaCertStore", e);
		}
	}

	private SignerInformationStore generateCounterSignature(CMSSignedData originalCMSSignedData, SignerInformation signerInformation,
			CAdESCounterSignatureParameters parameters, SignatureValue signatureValue) {
		CMSSignedDataBuilder builder = new CMSSignedDataBuilder(certificateVerifier);

		SignatureAlgorithm signatureAlgorithm = signatureValue.getAlgorithm();
		final CustomContentSigner customContentSigner = new CustomContentSigner(signatureAlgorithm.getJCEId(), signatureValue.getValue());

		final DigestCalculatorProvider dcp = CMSUtils.getDigestCalculatorProvider(new InMemoryDocument(signerInformation.getSignature()),
				parameters.getReferenceDigestAlgorithm());
		SignerInfoGeneratorBuilder signerInformationGeneratorBuilder = builder.getSignerInfoGeneratorBuilder(dcp, parameters, false);
		CMSSignedDataGenerator cmsSignedDataGenerator = builder.createCMSSignedDataGenerator(parameters, customContentSigner, signerInformationGeneratorBuilder,
				null);
		return CMSUtils.generateCounterSigners(cmsSignedDataGenerator, signerInformation);
	}

	public SignerInformation getSignerInformationToBeSigned(DSSDocument signatureDocument, String signatureIdToCounterSign) {
		CAdESSignature cadesSignature = getSignatureById(signatureDocument, signatureIdToCounterSign);
		if (cadesSignature == null) {
			throw new DSSException(String.format("CAdESSignature not found with the given dss id '%s'", signatureIdToCounterSign));
		}
		return cadesSignature.getSignerInformation();
	}

	private CAdESSignature getSignatureById(DSSDocument signatureDocument, String dssId) {
		CMSDocumentValidator validator = new CMSDocumentValidator(signatureDocument);
		List<AdvancedSignature> signatures = validator.getSignatures();
		return findSignatureRecursive(signatures, dssId);
	}

	private CAdESSignature findSignatureRecursive(List<AdvancedSignature> signatures, String dssId) {
		if (Utils.isCollectionNotEmpty(signatures)) {
			for (AdvancedSignature advancedSignature : signatures) {
				if (dssId.equals(advancedSignature.getId())) {
					if (containsATSTv2(advancedSignature)) {
						throw new DSSException("Cannot add a counter signature to a CAdES containing an archiveTimestampV2");
					}
					return (CAdESSignature) advancedSignature;
				}
				return findSignatureRecursive(advancedSignature.getCounterSignatures(), dssId);
			}
		}
		return null;
	}
	
	private boolean containsATSTv2(AdvancedSignature signature) {
		List<TimestampToken> archiveTimestamps = signature.getArchiveTimestamps();
		for (TimestampToken timestampToken : archiveTimestamps) {
			if (ArchiveTimestampType.CAdES_V2.equals(timestampToken.getArchiveTimestampType())) {
				return true;
			}
		}
		return false;
	}

}
