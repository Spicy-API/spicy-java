package ai.spicyapi;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * Writes a USD amount as the decimal string the contract specifies.
 *
 * <p>Amounts travel as strings precisely so that no binary floating point is involved, and
 * {@code toPlainString} is what keeps that promise: {@code BigDecimal.toString} would emit
 * {@code 1E-9} for a small enough scale, which does not match the contract's decimal pattern.
 */
final class UsdAmountSerializer extends JsonSerializer<BigDecimal> {

    @Override
    public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider serializers)
            throws IOException {
        generator.writeString(value.toPlainString());
    }
}
